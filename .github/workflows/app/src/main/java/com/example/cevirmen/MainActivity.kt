package com.example.cevirmen

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var model: Model? = null
    private var speechService: SpeechService? = null

    private var listening by mutableStateOf(false)
    private var voskReady by mutableStateOf(false)
    private var translatorReady by mutableStateOf(false)
    private var status by mutableStateOf("Ses modeli hazırlanıyor (ilk açılış biraz sürer)...")
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

        prepareVoskModel()

        // Çeviri modeli yalnızca ilk seferde internetle iner, sonra internetsiz çalışır
        translator.downloadModelIfNeeded()
            .addOnSuccessListener { translatorReady = true; updateReadyStatus() }
            .addOnFailureListener {
                status = "Çeviri modeli indirilemedi. İlk açılışta internet gerekli."
            }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    private fun updateReadyStatus() {
        if (voskReady && translatorReady) {
            status = "Hazır (internetsiz çalışır). Başlat'a dokun ve İngilizce konuş."
        }
    }

    // ---------- Ses modelini APK içinden telefona aç ----------

    private fun prepareVoskModel() {
        Thread {
            try {
                val target = File(filesDir, "vosk-model")
                val done = File(filesDir, "vosk-model.done")
                if (!done.exists()) {
                    target.deleteRecursively()
                    copyAssetDir("model", target)
                    done.writeText("ok")
                }
                val m = Model(target.absolutePath)
                runOnUiThread {
                    model = m
                    voskReady = true
                    updateReadyStatus()
                }
            } catch (e: Exception) {
                runOnUiThread { status = "Ses modeli yüklenemedi: ${e.message}" }
            }
        }.start()
    }

    private fun copyAssetDir(assetPath: String, target: File) {
        val children = assets.list(assetPath)
        if (children == null || children.isEmpty()) {
            try {
                target.parentFile?.mkdirs()
                assets.open(assetPath).use { input ->
                    FileOutputStream(target).use { out -> input.copyTo(out) }
                }
            } catch (e: IOException) {
                target.mkdirs() // boş klasör
            }
        } else {
            target.mkdirs()
            for (name in children) copyAssetDir("$assetPath/$name", File(target, name))
        }
    }

    // ---------- Arayüz ----------

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
                "İngilizce (duyulan)",
                (committedEn + " " + partialEn).trim(),
                Modifier.weight(1f)
            )
            TextCard(
                "Türkçe (çeviri)",
                (committedTr + " " + partialTr).trim(),
                Modifier.weight(1f)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { if (listening) stopListening() else requestAndStart() },
                    enabled = voskReady && translatorReady,
                    modifier = Modifier.weight(1f)
                ) { Text(if (listening) "Durdur" else "Başlat") }
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

    // ---------- Mikrofon / Vosk ----------

    private fun requestAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startListening() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startListening() {
        val m = model ?: return
        try {
            val recognizer = Recognizer(m, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f).also {
                it.startListening(listener)
            }
            listening = true
            status = "Dinliyorum..."
        } catch (e: Exception) {
            status = "Mikrofon başlatılamadı: ${e.message}"
        }
    }

    private fun stopListening() {
        speechService?.stop()
        speechService?.shutdown()
        speechService = null
        listening = false
        partialEn = ""; partialTr = ""
        status = "Durdu."
    }

    // Vosk çıktısı küçük harfli ve noktasızdır; çeviri kalitesi için düzeltiriz
    private fun tidy(text: String): String =
        text.trim().replaceFirstChar { it.uppercase() } + "."

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val text = JSONObject(hypothesis ?: return).optString("partial")
            if (text.isBlank() || text == partialEn) return
            partialEn = text
            translator.translate(text).addOnSuccessListener { partialTr = it }
        }

        override fun onResult(hypothesis: String?) {
            commit(JSONObject(hypothesis ?: return).optString("text"))
        }

        override fun onFinalResult(hypothesis: String?) {
            commit(JSONObject(hypothesis ?: return).optString("text"))
        }

        override fun onError(exception: Exception?) {
            status = "Hata: ${exception?.message}"
        }

        override fun onTimeout() {}
    }

    private fun commit(raw: String) {
        partialEn = ""; partialTr = ""
        if (raw.isBlank()) return
        val en = tidy(raw)
        committedEn = (committedEn + " " + en).trim()
        translator.translate(en).addOnSuccessListener {
            committedTr = (committedTr + " " + it).trim()
        }
    }

    override fun onDestroy() {
        speechService?.shutdown()
        model?.close()
        translator.close()
        super.onDestroy()
    }
}
