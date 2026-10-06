package com.example.cevirmen

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions

class MainActivity : ComponentActivity() {

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())

    // Ekrandaki durumlar
    private var listening by mutableStateOf(false)
    private var modelReady by mutableStateOf(false)
    private var status by mutableStateOf("Çeviri modeli indiriliyor...")
    private var committedEn by mutableStateOf("")
    private var committedTr by mutableStateOf("")
    private var partialEn by mutableStateOf("")
    private var partialTr by mutableStateOf("")

    private val translator by lazy {
        Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.TURKISH)
                .build()
        )
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startListening() else status = "Mikrofon izni gerekli"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // İlk açılışta İngilizce->Türkçe modeli bir kez indirilir (~30 MB)
        translator.downloadModelIfNeeded()
            .addOnSuccessListener {
                modelReady = true
                status = "Hazır. Başlat'a dokun ve İngilizce konuş."
            }
            .addOnFailureListener {
                status = "Model indirilemedi. İnternet bağlantını kontrol et."
            }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Screen()
                }
            }
        }
    }

    @Composable
    private fun Screen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Çevirmen (EN → TR)", fontSize = 22.sp)
            Text(status, fontSize = 13.sp)

            TextCard(
                title = "İngilizce (duyulan)",
                text = (committedEn + " " + partialEn).trim(),
                modifier = Modifier.weight(1f)
            )
            TextCard(
                title = "Türkçe (çeviri)",
                text = (committedTr + " " + partialTr).trim(),
                modifier = Modifier.weight(1f)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { if (listening) stopListening() else requestAndStart() },
                    enabled = modelReady,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (listening) "Durdur" else "Başlat")
                }
                OutlinedButton(
                    onClick = {
                        committedEn = ""; committedTr = ""
                        partialEn = ""; partialTr = ""
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Temizle") }
            }
        }
    }

    @Composable
    private fun TextCard(title: String, text: String, modifier: Modifier) {
        Card(modifier = modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(title, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text(text.ifEmpty { "..." }, fontSize = 20.sp)
            }
        }
    }

    // ---------- Mikrofon / Konuşma tanıma ----------

    private fun requestAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status = "Bu telefonda konuşma tanıma servisi yok (Google uygulaması gerekli)."
            return
        }
        listening = true
        status = "Dinliyorum..."
        beginSession()
    }

    private fun stopListening() {
        listening = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        partialEn = ""; partialTr = ""
        status = "Durdu."
    }

    private fun beginSession() {
        if (!listening) return
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer?.startListening(intent)
    }

    // Tanıyıcı her cümleden sonra durur; dinlemeyi kısa gecikmeyle yeniden başlatırız
    private fun restartSoon() {
        if (listening) handler.postDelayed({ beginSession() }, 300)
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            if (text.isBlank()) return
            partialEn = text
            translator.translate(text).addOnSuccessListener { partialTr = it }
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull().orEmpty()
            partialEn = ""; partialTr = ""
            if (text.isNotBlank()) {
                committedEn = (committedEn + " " + text).trim()
                translator.translate(text).addOnSuccessListener {
                    committedTr = (committedTr + " " + it).trim()
                }
            }
            restartSoon()
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    stopListening(); status = "Mikrofon izni verilmemiş."
                }
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> status = "Ağ hatası, tekrar deniyorum..."
                else -> { /* sessizlik / eşleşme yok: sadece yeniden başlat */ }
            }
            restartSoon()
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        translator.close()
        super.onDestroy()
    }
}
