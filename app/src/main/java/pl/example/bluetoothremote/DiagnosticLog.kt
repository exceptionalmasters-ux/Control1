package pl.example.bluetoothremote

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLog {
    private const val PREFS = "diagnostic_log"
    private const val KEY = "entries"
    private const val MAX_CHARS = 60000
    @Volatile private var installed = false
    private var appContext: Context? = null

    @Synchronized
    fun install(context: Context) {
        appContext = context.applicationContext
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            append("CRASH thread=${thread.name}: ${error.stackTraceToString()}")
            previous?.uncaughtException(thread, error)
        }
        append("=== PROCESS START version=0.20.0 sdk=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER}/${Build.MODEL} ===")
    }

    @Synchronized
    fun append(message: String) {
        val context = appContext ?: return
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$time [${Thread.currentThread().name}] $message\n"
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = prefs.getString(KEY, "").orEmpty()
        prefs.edit().putString(KEY, (old + line).takeLast(MAX_CHARS)).commit()
        Log.d("ControlOneDiag", message)
    }

    fun copyToClipboard(activity: Activity) {
        append("Diagnostic log copied by user")
        val text = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Control One diagnostic log", text))
    }
}
