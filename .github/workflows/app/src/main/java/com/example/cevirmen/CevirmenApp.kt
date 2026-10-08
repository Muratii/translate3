package com.example.cevirmen

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

// Uygulama çökerse hata metnini dosyaya yazar; sonraki açılışta ekranda gösterilir
class CevirmenApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(filesDir, "crash.txt").writeText("Thread: ${t.name}\n$sw")
            } catch (_: Throwable) {
            }
            old?.uncaughtException(t, e)
        }
    }
}
