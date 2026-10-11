package pl.example.bluetoothremote

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var host: EditText
    private lateinit var code: EditText
    private lateinit var pairButton: Button
    private lateinit var confirmButton: Button
    private var pairing: TvPairing? = null
    private var remote: TvRemote? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText); host = findViewById(R.id.hostInput)
        code = findViewById(R.id.codeInput); pairButton = findViewById(R.id.pairButton)
        confirmButton = findViewById(R.id.confirmButton)
        host.setText(getSharedPreferences("tv", MODE_PRIVATE).getString("host", ""))
        pairButton.setOnClickListener { startPairing() }
        confirmButton.setOnClickListener { finishPairing() }
        findViewById<Button>(R.id.connectButton).setOnClickListener { connect() }
        key(R.id.upButton, TvKey.UP); key(R.id.downButton, TvKey.DOWN)
        key(R.id.leftButton, TvKey.LEFT); key(R.id.rightButton, TvKey.RIGHT)
        key(R.id.okButton, TvKey.OK); key(R.id.backButton, TvKey.BACK)
        key(R.id.homeButton, TvKey.HOME); key(R.id.volumeDownButton, TvKey.VOL_DOWN)
        key(R.id.volumeUpButton, TvKey.VOL_UP); key(R.id.playPauseButton, TvKey.PLAY_PAUSE)
        key(R.id.powerButton, TvKey.POWER)
        findViewById<Button>(R.id.sendTextButton).setOnClickListener {
            val value = findViewById<EditText>(R.id.textInput).text.toString()
            if (remote?.sendText(value) == true) show("Wysłano tekst")
            else show("Najpierw połącz TV i ustaw na nim kursor w polu tekstowym")
        }
        show("Telefon i TV muszą być w tej samej sieci Wi‑Fi. Wpisz adres IP telewizora.")
    }

    private fun address() = host.text.toString().trim()
    private fun startPairing() {
        if (address().isEmpty()) return show("Wpisz adres IP telewizora, np. 192.168.1.50")
        busy(true); show("Łączenie z usługą parowania TV…")
        lifecycleScope.launch {
            val session = TvPairing(this@MainActivity, address())
            val result = withContext(Dispatchers.IO) { session.start(android.os.Build.MODEL) }
            busy(false); result.onSuccess {
                pairing = session; code.visibility = View.VISIBLE; confirmButton.visibility = View.VISIBLE
                show("Przepisz 6-znakowy kod wyświetlony na telewizorze.")
            }.onFailure { show(errorText(it)) }
        }
    }
    private fun finishPairing() {
        val session = pairing ?: return show("Najpierw kliknij „Paruj z TV”")
        busy(true); show("Sprawdzanie kodu…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { session.complete(code.text.toString()) }
            busy(false); result.onSuccess {
                pairing = null; saveHost(); show("Sparowano. Łączenie pilota…"); connect()
            }.onFailure { show(errorText(it)) }
        }
    }
    private fun connect() {
        if (address().isEmpty()) return show("Wpisz adres IP telewizora")
        remote?.close(); remote = null; show("Łączenie z telewizorem…")
        lifecycleScope.launch {
            val client = TvRemote(this@MainActivity, address()) { runOnUiThread { show(it) } }
            val result = withContext(Dispatchers.IO) { client.connect() }
            result.onSuccess { remote = client; saveHost(); show("Połączono — pilot jest gotowy") }
                .onFailure { client.close(); show(errorText(it)) }
        }
    }
    private fun saveHost() = getSharedPreferences("tv", MODE_PRIVATE).edit().putString("host", address()).apply()
    private fun key(id: Int, value: Int) = findViewById<Button>(id).setOnClickListener {
        if (remote?.sendKey(value) != true) show("Najpierw połącz telewizor")
    }
    private fun busy(value: Boolean) { pairButton.isEnabled = !value; confirmButton.isEnabled = !value }
    private fun show(message: String) { status.text = message }
    private fun errorText(t: Throwable): String = when {
        t.message?.contains("refused", true) == true -> "TV odrzucił połączenie. Sprawdź IP i usługę Android TV Remote."
        t.message?.contains("timed out", true) == true -> "Brak odpowiedzi. Sprawdź IP i wspólną sieć Wi‑Fi."
        else -> "Błąd: ${t.message ?: "nie udało się połączyć"}"
    }
    override fun onDestroy() { pairing?.close(); remote?.close(); super.onDestroy() }
}
