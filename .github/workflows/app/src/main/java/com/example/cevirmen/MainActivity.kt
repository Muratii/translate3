package com.example.cevirmen

import android.Manifest
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

// İndirilen bayt sayısını sayan yardımcı sınıf (ilerleme göstermek için)
private class CountingStream(input: InputStream) : FilterInputStream(input) {
    @Volatile var count = 0L
    override fun read(): Int {
        val r = super.read()
        if (r >= 0) count++
        return r
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val r = super.read(b, off, len)
        if (r > 0) count += r
        return r
    }
}

class MainActivity : ComponentActivity() {

    private class ModelInfo(val key: String, val label: String, val url: String, val minFree: Long)

    private val small = ModelInfo(
        "small", "Küçük (36 MB)",
        "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip", 300_000_000L
    )
    private val big = ModelInfo(
        "big", "Büyük (1 GB)",
        "https://alphacephei.com/vosk/models/vosk-model-en-in-0.5.zip", 3_000_000_000L
    )

    private lateinit var prefs: SharedPreferences
    private var model: Model? = null
    private var speechService: SpeechService? = null

    private var selectedKey by mutableStateOf("small")
    private var smallInstalled by mutableStateOf(false)
    private var bigInstalled by mutableStateOf(false)
    private var downloading by mutableStateOf(false)
    private var listening by mutableStateOf(false)
    private var voskReady by mutableStateOf(false)
    private var translatorReady by mutableStateOf(false)
    private var status by mutableStateOf("Başlıyor...")
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

    private fun infoOf(key: String) = if (key == "big") big else small
    private fun modelDir(key: String) = File(filesDir, "models/$key")
    private fun isInstalled(key: String) = File(modelDir(key), ".ok").exists()
    private fun installedState(key: String) = if (key == "big") bigInstalled else smallInstalled
    private fun refreshInstalled() {
        smallInstalled = isInstalled("small")
        bigInstalled = isInstalled("big")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashFile = File(filesDir, "crash.txt")
        if (crashFile.exists()) {
            val text = try { crashFile.readText() } catch (e: Throwable) { "okunamadı" }
            crashFile.delete()
            showCrashScreen(
                "Uygulama önceki açılışta çöktü. Bu yazının ekran görüntüsünü gönder:",
                text
            ) { startApp() }
        } else {
            try {
                startApp()
            } catch (e: Throwable) {
                showCrashScreen(
                    "Başlatma hatası. Bu yazının ekran görüntüsünü gönder:",
                    Log.getStackTraceString(e),
                    null
                )
            }
        }
    }

    private fun showCrashScreen(title: String, trace: String, onContinue: (() -> Unit)?) {
        val shown = if (trace.length > 4000) trace.take(1500) + "\n...\n" + trace.takeLast(2500) else trace
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 96, 32, 32)
        }
        root.addView(TextView(this).apply { text = title; textSize = 16f })
        val tv = TextView(this).apply {
            text = shown
            textSize = 11f
            setTextIsSelectable(true)
        }
        root.addView(
            ScrollView(this).apply { addView(tv) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        if (onContinue != null) {
            root.addView(Button(this).apply {
                text = "Uygulamayı yine de aç"
                setOnClickListener { onContinue() }
            })
        }
        setContentView(root)
    }

    private fun startApp() {
        prefs = getSharedPreferences("cevirmen", MODE_PRIVATE)

        // Önceki model yüklemesi uygulamayı çökerttiyse (bellek yetmedi) küçük modele dön
        var notice = ""
        if (prefs.getString("loading", null) != null) {
            prefs.edit().remove("loading").putString("selected", "small").commit()
            notice = "Önceki model yüklenirken uygulama kapandı (telefonun belleği yetmemiş olabilir). Küçük modele dönüldü. "
        }
        selectedKey = prefs.getString("selected", "small") ?: "small"
        refreshInstalled()

        if (isInstalled(selectedKey)) {
            loadModel(selectedKey)
        } else {
            status = notice + "Ses modeli henüz indirilmedi. Aşağıdaki düğmeyle indir (Wi-Fi önerilir)."
        }
        if (notice.isNotEmpty() && isInstalled(selectedKey)) status = notice

        // Çeviri modeli yalnızca ilk seferde internetle iner
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
        if (voskReady && translatorReady && !downloading) {
            status = "Hazır (internetsiz çalışır). Başlat'a dokun ve İngilizce konuş."
        }
    }

    // ---------- Model seçme / indirme / yükleme ----------

    private fun selectModel(key: String) {
        if (listening) stopListening()
        selectedKey = key
        prefs.edit().putString("selected", key).commit()
        voskReady = false
        model?.close()
        model = null
        if (isInstalled(key)) {
            loadModel(key)
        } else {
            status = "Bu model henüz indirilmedi. Aşağıdaki düğmeyle indir."
        }
    }

    private fun loadModel(key: String) {
        voskReady = false
        status = "Ses modeli yükleniyor... (büyük model 1-2 dakika sürebilir)"
        Thread {
            // Bu işaret, yükleme sırasında uygulama kapanırsa bir sonraki açılışta anlaşılmasını sağlar
            prefs.edit().putString("loading", key).commit()
            try {
                val m = Model(modelDir(key).absolutePath)
                prefs.edit().remove("loading").commit()
                runOnUiThread {
                    model = m
                    voskReady = true
                    updateReadyStatus()
                }
            } catch (e: Throwable) {
                prefs.edit().remove("loading").commit()
                runOnUiThread {
                    status = "Model yüklenemedi (${e.javaClass.simpleName}): ${e.message}"
                }
            }
        }.start()
    }

    private fun downloadModel(info: ModelInfo) {
        if (downloading) return
        if (filesDir.usableSpace < info.minFree) {
            status = "Yetersiz depolama alanı. En az ${info.minFree / 1_000_000_000.0} GB boş yer gerekir."
            return
        }
        downloading = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = "İndirme başlıyor... Ekranı kapatma, uygulamadan çıkma."

        Thread {
            val dir = modelDir(info.key)
            try {
                dir.deleteRecursively()
                dir.mkdirs()
                val conn = URL(info.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                conn.connect()
                val total = conn.contentLengthLong
                val counter = CountingStream(conn.inputStream)
                var lastReport = 0L

                ZipInputStream(BufferedInputStream(counter, 1 shl 16)).use { zin ->
                    var entry = zin.nextEntry
                    val buf = ByteArray(1 shl 16)
                    while (entry != null) {
                        // zip içindeki üst klasör adını at
                        val rel = entry.name.substringAfter('/', "")
                        if (rel.isNotEmpty()) {
                            val out = File(dir, rel)
                            if (!out.canonicalPath.startsWith(dir.canonicalPath)) {
                                throw SecurityException("Geçersiz dosya yolu")
                            }
                            if (entry.isDirectory) {
                                out.mkdirs()
                            } else {
                                out.parentFile?.mkdirs()
                                FileOutputStream(out).use { fos ->
                                    while (true) {
                                        val n = zin.read(buf)
                                        if (n < 0) break
                                        fos.write(buf, 0, n)
                                        val c = counter.count
                                        if (c - lastReport > 2_000_000) {
                                            lastReport = c
                                            val mb = c / 1_000_000
                                            val text = if (total > 0)
                                                "İndiriliyor: $mb / ${total / 1_000_000} MB (%${c * 100 / total})"
                                            else "İndiriliyor: $mb MB"
                                            runOnUiThread { status = text }
                                        }
                                    }
                                }
                            }
                        }
                        zin.closeEntry()
                        entry = zin.nextEntry
                    }
                }
                File(dir, ".ok").writeText("ok")
                runOnUiThread {
                    downloading = false
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    refreshInstalled()
                    if (selectedKey == info.key) loadModel(info.key)
                }
            } catch (e: Throwable) {
                dir.deleteRecursively()
                runOnUiThread {
                    downloading = false
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    refreshInstalled()
                    status = "İndirme başarısız (${e.javaClass.simpleName}): ${e.message}. Tekrar dene."
                }
            }
        }.start()
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Çevirmen (EN → TR)", fontSize = 22.sp)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(small, big).forEach { info ->
                    val label = info.label + if (installedState(info.key)) " ✓" else ""
                    if (selectedKey == info.key) {
                        Button(
                            onClick = { selectModel(info.key) },
                            enabled = !downloading,
                            modifier = Modifier.weight(1f)
                        ) { Text(label, fontSize = 13.sp) }
                    } else {
                        OutlinedButton(
                            onClick = { selectModel(info.key) },
                            enabled = !downloading,
                            modifier = Modifier.weight(1f)
                        ) { Text(label, fontSize = 13.sp) }
                    }
                }
            }

            if (!installedState(selectedKey) && !downloading) {
                Button(
                    onClick = { downloadModel(infoOf(selectedKey)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("${infoOf(selectedKey).label} modeli indir") }
            }

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
                    enabled = voskReady && translatorReady && !downloading,
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
