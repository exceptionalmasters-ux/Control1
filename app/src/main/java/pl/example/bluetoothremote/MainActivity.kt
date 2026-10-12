package pl.example.bluetoothremote

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var host: EditText
    private lateinit var code: EditText
    private lateinit var pairButton: Button
    private lateinit var confirmButton: Button
    private lateinit var setupPanel: View
    private lateinit var remotePanel: View
    private var pairing: TvPairing? = null
    private var remote: TvRemote? = null
    private var shiftOn = false
    private val keyboardBuffer = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private var lastKeyboardTapAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText); host = findViewById(R.id.hostInput)
        code = findViewById(R.id.codeInput); pairButton = findViewById(R.id.pairButton)
        confirmButton = findViewById(R.id.confirmButton)
        setupPanel = findViewById(R.id.setupPanel); remotePanel = findViewById(R.id.remotePanel)
        host.setText(getSharedPreferences("tv", MODE_PRIVATE).getString("host", ""))
        pairButton.setOnClickListener { startPairing() }
        confirmButton.setOnClickListener { finishPairing() }
        findViewById<Button>(R.id.connectButton).setOnClickListener { connect() }
        findViewById<Button>(R.id.showSetupButton).setOnClickListener { setupPanel.visibility = View.VISIBLE }
        key(R.id.upButton, TvKey.UP); key(R.id.downButton, TvKey.DOWN)
        key(R.id.leftButton, TvKey.LEFT); key(R.id.rightButton, TvKey.RIGHT)
        key(R.id.okButton, TvKey.OK); key(R.id.backButton, TvKey.BACK)
        key(R.id.homeButton, TvKey.HOME); key(R.id.volumeDownButton, TvKey.VOL_DOWN)
        key(R.id.volumeUpButton, TvKey.VOL_UP); key(R.id.playPauseButton, TvKey.PLAY_PAUSE)
        key(R.id.powerButton, TvKey.POWER)
        val styledButtons = intArrayOf(R.id.showSetupButton,R.id.pairButton,R.id.connectButton,R.id.confirmButton,R.id.upButton,R.id.downButton,R.id.leftButton,R.id.rightButton,R.id.okButton,R.id.backButton,R.id.homeButton,R.id.volumeDownButton,R.id.volumeUpButton,R.id.playPauseButton,R.id.powerButton,R.id.keyboardToggleButton,R.id.shiftButton,R.id.spaceButton,R.id.enterButton,R.id.backspaceButton,R.id.clearTextButton,R.id.openUrlButton,R.id.searchWebButton)
        styledButtons.forEach { findViewById<Button>(it).backgroundTintList = null }
        setupKeyboard()
        findViewById<Button>(R.id.backspaceButton).setOnClickListener {
            if (!acceptKeyboardTap()) return@setOnClickListener
            if (remote?.backspace() != true) show("Najpierw połącz telewizor")
            else if (keyboardBuffer.isNotEmpty()) {
                keyboardBuffer.deleteCharAt(keyboardBuffer.length - 1)
                refreshKeyboardBuffer()
            }
        }
        findViewById<Button>(R.id.clearTextButton).setOnClickListener {
            val client=remote
            if(client?.clearByHoldingBackspace()!=true)show("Najpierw połącz telewizor")
            else { keyboardBuffer.clear();refreshKeyboardBuffer();show("Czyszczenie pola przez 3 sekundy…");handler.postDelayed({client.sendKey(TvKey.DEL,2)},3000) }
        }
        findViewById<TouchpadView>(R.id.touchpad).listener = object : TouchpadView.Listener {
            override fun move(key: Int, repeats: Int) {
                val client = remote ?: return show("Najpierw połącz telewizor")
                client.sendKey(key, 1)
            }
            override fun stop(key: Int) { remote?.sendKey(key, 2) }
            override fun click() { remote?.sendKey(TvKey.OK) ?: show("Najpierw połącz telewizor") }
        }
        findViewById<Button>(R.id.openUrlButton).setOnClickListener {
            val raw=findViewById<EditText>(R.id.browserInput).text.toString().trim()
            if(raw.isEmpty())show("Wpisz adres strony") else {
                val url=if(raw.startsWith("http://")||raw.startsWith("https://"))raw else "https://$raw"
                if(remote?.openUrl(url)==true)show("Otwieranie: $url")else show("Najpierw połącz telewizor")
            }
        }
        findViewById<Button>(R.id.searchWebButton).setOnClickListener {
            val query=findViewById<EditText>(R.id.browserInput).text.toString().trim()
            val url="https://www.google.com/search?q="+URLEncoder.encode(query,"UTF-8")
            if(query.isEmpty())show("Wpisz zapytanie")else if(remote?.openUrl(url)==true)show("Wysyłanie wyszukiwania")else show("Najpierw połącz telewizor")
        }
        show("Telefon i TV muszą być w tej samej sieci Wi‑Fi. Wpisz adres IP telewizora.")
    }

    private fun address() = host.text.toString().trim()
    private fun setupKeyboard() {
        val panel=findViewById<View>(R.id.keyboardPanel)
        findViewById<Button>(R.id.keyboardToggleButton).setOnClickListener { panel.visibility=if(panel.visibility==View.VISIBLE)View.GONE else View.VISIBLE }
        addKeyRow(R.id.keyRow1,"qwertyuiop");addKeyRow(R.id.keyRow2,"asdfghjkl");addKeyRow(R.id.keyRow3,"zxcvbnm,.")
        val shift=findViewById<Button>(R.id.shiftButton)
        shift.setOnClickListener { shiftOn=!shiftOn;shift.isSelected=shiftOn }
        findViewById<Button>(R.id.spaceButton).setOnClickListener { typeOnTv(' ') }
        findViewById<Button>(R.id.enterButton).setOnClickListener { if(remote?.sendKey(TvKey.ENTER)!=true)show("Najpierw połącz telewizor") }
    }
    private fun addKeyRow(rowId:Int,chars:String){
        val row=findViewById<LinearLayout>(rowId)
        chars.forEach { ch ->
            val button=Button(this).apply {
                text=ch.toString().uppercase();gravity=Gravity.CENTER;textSize=11f;minWidth=0;minHeight=0
                backgroundTintList=null;setBackgroundResource(R.drawable.button_outline)
                setTextColor(ContextCompat.getColorStateList(this@MainActivity,R.color.button_text))
                layoutParams=LinearLayout.LayoutParams(0,matchParentHeight(),1f).apply{setMargins(2,1,2,1)}
                setOnClickListener { typeOnTv(if(shiftOn)ch.uppercaseChar() else ch);if(shiftOn){shiftOn=false;findViewById<Button>(R.id.shiftButton).isSelected=false} }
            };row.addView(button)
        }
    }
    private fun matchParentHeight()=LinearLayout.LayoutParams.MATCH_PARENT
    private fun typeOnTv(char:Char){
        if (!acceptKeyboardTap()) return
        if(remote?.sendText(char.toString())!=true) show("Najpierw połącz telewizor")
        else { keyboardBuffer.append(char);refreshKeyboardBuffer() }
    }
    private fun acceptKeyboardTap():Boolean {
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-lastKeyboardTapAt<140L)return false
        lastKeyboardTapAt=now
        return true
    }
    private fun refreshKeyboardBuffer(){findViewById<TextView>(R.id.keyboardBuffer).text=keyboardBuffer.toString().ifEmpty{"Wpisywany tekst pojawi się tutaj"}}
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
                pairing = null
                getSharedPreferences("tv", MODE_PRIVATE).edit().putBoolean("paired", true).apply()
                saveHost(); show("Sparowano. Łączenie pilota…"); connect()
            }.onFailure { show(errorText(it)) }
        }
    }
    private fun connect() {
        if (address().isEmpty()) return show("Wpisz adres IP telewizora")
        remote?.close(); remote = null; show("Łączenie z telewizorem…")
        lifecycleScope.launch {
            val client = TvRemote(this@MainActivity, address()) {
                runOnUiThread { remote = null; show("$it — po powrocie połączę ponownie") }
            }
            val result = withContext(Dispatchers.IO) { client.connect() }
            result.onSuccess {
                remote = client; saveHost(); setupPanel.visibility = View.GONE
                remotePanel.visibility = View.VISIBLE; show("Połączono — pilot jest gotowy")
            }
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

    override fun onResume() {
        super.onResume()
        if (::host.isInitialized && remote == null && host.text.isNotBlank() &&
            getSharedPreferences("tv", MODE_PRIVATE).getBoolean("paired", false)) connect()
    }
}
