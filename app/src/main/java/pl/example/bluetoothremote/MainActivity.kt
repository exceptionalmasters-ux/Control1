package pl.example.bluetoothremote

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

private enum class AppMode { TV, PC }

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
    private var pc: PcClient? = null
    private var appMode = AppMode.TV
    private var shiftOn = false
    private var symbolsOn = false
    private var textMode = TextMode.AUTO
    private val keyboardBuffer = StringBuilder()
    private val handler = Handler(Looper.getMainLooper())
    private var lastKeyboardTapAt = 0L
    private var backspaceHeld = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); setContentView(R.layout.activity_main)
        status = findViewById(R.id.statusText); host = findViewById(R.id.hostInput)
        code = findViewById(R.id.codeInput); pairButton = findViewById(R.id.pairButton)
        confirmButton = findViewById(R.id.confirmButton)
        setupPanel = findViewById(R.id.setupPanel); remotePanel = findViewById(R.id.remotePanel)
        host.setText(getSharedPreferences("tv", MODE_PRIVATE).getString("host", "192.168.0.100"))
        pairButton.setOnClickListener { if(appMode==AppMode.TV)startPairing() else startPcPairing() }
        confirmButton.setOnClickListener { finishPairing() }
        findViewById<Button>(R.id.connectButton).setOnClickListener { connect() }
        findViewById<Button>(R.id.deviceModeButton).setOnClickListener { switchDeviceMode() }
        findViewById<Button>(R.id.showSetupButton).setOnClickListener {
            setupPanel.visibility=if(setupPanel.visibility==View.VISIBLE)View.GONE else View.VISIBLE
        }
        key(R.id.upButton, TvKey.UP); key(R.id.downButton, TvKey.DOWN)
        key(R.id.leftButton, TvKey.LEFT); key(R.id.rightButton, TvKey.RIGHT)
        key(R.id.okButton, TvKey.OK); key(R.id.backButton, TvKey.BACK)
        key(R.id.homeButton, TvKey.HOME); key(R.id.volumeDownButton, TvKey.VOL_DOWN)
        key(R.id.volumeUpButton, TvKey.VOL_UP); key(R.id.playPauseButton, TvKey.PLAY_PAUSE)
        key(R.id.muteButton, TvKey.MUTE);key(R.id.channelDownButton,TvKey.CHANNEL_DOWN);key(R.id.channelUpButton,TvKey.CHANNEL_UP)
        key(R.id.powerButton, TvKey.POWER)
        val styledButtons = intArrayOf(R.id.deviceModeButton,R.id.showSetupButton,R.id.pairButton,R.id.connectButton,R.id.confirmButton,R.id.upButton,R.id.downButton,R.id.leftButton,R.id.rightButton,R.id.okButton,R.id.backButton,R.id.homeButton,R.id.volumeDownButton,R.id.volumeUpButton,R.id.playPauseButton,R.id.muteButton,R.id.channelDownButton,R.id.channelUpButton,R.id.powerButton,R.id.keyboardToggleButton,R.id.textModeButton,R.id.symbolsButton,R.id.shiftButton,R.id.spaceButton,R.id.sendTextButton,R.id.enterButton,R.id.backspaceButton,R.id.openUrlButton,R.id.searchWebButton)
        styledButtons.forEach { findViewById<Button>(it).backgroundTintList = null }
        setupTvNumbers()
        setupKeyboard()
        setupBackspace()
        findViewById<TouchpadView>(R.id.touchpad).listener = object : TouchpadView.Listener {
            override fun move(key: Int, repeats: Int) {
                if(appMode==AppMode.PC)return
                val client = remote ?: return show("Najpierw połącz telewizor");client.sendKey(key, 1)
            }
            override fun stop(key: Int) { if(appMode==AppMode.TV)remote?.sendKey(key, 2) }
            override fun click() { if(appMode==AppMode.PC){if(pc?.click()!=true)show("Najpierw połącz komputer")}else remote?.sendKey(TvKey.OK) ?: show("Najpierw połącz telewizor") }
            override fun pointer(dx:Float,dy:Float):Boolean {if(appMode!=AppMode.PC)return false;pc?.mouse((dx*0.7f).toInt(),(dy*0.7f).toInt())?:show("Najpierw połącz komputer");return true}
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

    private fun switchDeviceMode(){
        saveHost();remote?.close();remote=null;pc?.close();pc=null
        appMode=if(appMode==AppMode.TV)AppMode.PC else AppMode.TV
        val prefs=getSharedPreferences(if(appMode==AppMode.TV)"tv" else "pc",MODE_PRIVATE)
        host.setText(prefs.getString("host",if(appMode==AppMode.TV)"192.168.0.100" else "192.168.0.2"))
        val pcMode=appMode==AppMode.PC
        findViewById<Button>(R.id.deviceModeButton).text=if(pcMode)"TV" else "PC"
        findViewById<Button>(R.id.showSetupButton).text=if(pcMode)"PC" else "TV"
        findViewById<TextView>(R.id.modeSubtitle).text=if(pcMode)"Pilot Windows przez Wi‑Fi" else "Pilot Android TV przez Wi‑Fi"
        findViewById<TextView>(R.id.setupTitle).text=if(pcMode)"Połączenie z komputerem" else "Połączenie z telewizorem"
        pairButton.text=if(pcMode)"Paruj PC" else "Paruj"
        code.visibility=if(pcMode)View.VISIBLE else View.GONE;code.hint=if(pcMode)"6-cyfrowy kod z programu Windows" else "6-znakowy kod z TV"
        confirmButton.visibility=View.GONE
        findViewById<View>(R.id.browserPanel).visibility=if(pcMode)View.GONE else View.VISIBLE
        findViewById<View>(R.id.textModeButton).visibility=if(pcMode)View.GONE else View.VISIBLE
        findViewById<View>(R.id.tvNumberPanel).visibility=if(!pcMode&&findViewById<View>(R.id.keyboardPanel).visibility!=View.VISIBLE)View.VISIBLE else View.GONE
        setupPanel.visibility=View.VISIBLE
        show(if(pcMode)"Uruchom Control One Receiver na komputerze, wpisz IP i kod." else "Wpisz adres IP telewizora.")
    }

    private fun address() = host.text.toString().trim()
    private fun setupKeyboard() {
        val panel=findViewById<View>(R.id.keyboardPanel)
        findViewById<Button>(R.id.keyboardToggleButton).setOnClickListener {
            panel.visibility=if(panel.visibility==View.VISIBLE)View.GONE else View.VISIBLE
            findViewById<View>(R.id.tvNumberPanel).visibility=if(appMode==AppMode.TV&&panel.visibility!=View.VISIBLE)View.VISIBLE else View.GONE
        }
        val modeButton=findViewById<Button>(R.id.textModeButton)
        modeButton.setOnClickListener {
            textMode=when(textMode){TextMode.AUTO->TextMode.IME;TextMode.IME->TextMode.LEGACY;TextMode.LEGACY->TextMode.AUTO}
            modeButton.text=when(textMode){TextMode.AUTO->"Tryb: AUTO";TextMode.IME->"Tryb: IME";TextMode.LEGACY->"Tryb: ZWYKŁY"}
            remote?.setTextMode(textMode)
        }
        rebuildKeyboard()
        val symbols=findViewById<Button>(R.id.symbolsButton)
        symbols.setOnClickListener { symbolsOn=!symbolsOn;symbols.text=if(symbolsOn)"ABC" else "123";shiftOn=false;findViewById<Button>(R.id.shiftButton).isSelected=false;rebuildKeyboard() }
        val shift=findViewById<Button>(R.id.shiftButton)
        shift.setOnClickListener { if(!symbolsOn){shiftOn=!shiftOn;shift.isSelected=shiftOn} }
        findViewById<Button>(R.id.spaceButton).setOnClickListener { typeCharacter(' ') }
        findViewById<Button>(R.id.sendTextButton).setOnClickListener {
            val text=keyboardBuffer.toString()
            if(text.isEmpty())show("Najpierw wpisz tekst")
            else if(if(appMode==AppMode.PC)pc?.text(text)==true else remote?.sendText(text)==true)show("Wysłano tekst")
            else show(if(appMode==AppMode.PC)"Najpierw połącz komputer" else "Najpierw połącz telewizor")
        }
        findViewById<Button>(R.id.enterButton).setOnClickListener { if(appMode==AppMode.PC){if(pc?.key("enter")!=true)show("Najpierw połącz komputer")}else if(remote?.sendKey(TvKey.ENTER)!=true)show("Najpierw połącz telewizor") }
    }
    private fun setupTvNumbers(){
        val grid=findViewById<GridLayout>(R.id.numberGrid)
        val order=listOf(1,2,3,4,5,6,7,8,9,-1,0,-1)
        order.forEach { value ->
            if(value<0){grid.addView(View(this),GridLayout.LayoutParams().apply{width=0;height=0;columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);rowSpec=GridLayout.spec(GridLayout.UNDEFINED,1f)})}
            else {
                val button=Button(this).apply{
                    text=value.toString();textSize=15f;minWidth=0;minHeight=0;backgroundTintList=null;setBackgroundResource(R.drawable.button_outline)
                    setTextColor(ContextCompat.getColorStateList(this@MainActivity,R.color.button_text))
                    setOnClickListener{if(remote?.sendKey(TvKey.digit(value))!=true)show("Najpierw połącz telewizor")}
                }
                grid.addView(button,GridLayout.LayoutParams().apply{width=0;height=0;columnSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);rowSpec=GridLayout.spec(GridLayout.UNDEFINED,1f);setMargins(2,2,2,2)})
            }
        }
    }
    private fun rebuildKeyboard(){
        val rows=if(symbolsOn) arrayOf("1234567890","@#\u20ac_&-+()","*/\"':;!?") else arrayOf("qwertyuiop","asdfghjkl","zxcvbnm,.")
        intArrayOf(R.id.keyRow1,R.id.keyRow2,R.id.keyRow3).forEachIndexed { index,id -> findViewById<LinearLayout>(id).removeAllViews();addKeyRow(id,rows[index]) }
    }
    private fun addKeyRow(rowId:Int,chars:String){
        val row=findViewById<LinearLayout>(rowId)
        chars.forEach { ch ->
            val button=Button(this).apply {
                text=ch.toString().uppercase();gravity=Gravity.CENTER;textSize=11f;minWidth=0;minHeight=0
                backgroundTintList=null;setBackgroundResource(R.drawable.button_outline)
                setTextColor(ContextCompat.getColorStateList(this@MainActivity,R.color.button_text))
                layoutParams=LinearLayout.LayoutParams(0,matchParentHeight(),1f).apply{setMargins(2,1,2,1)}
                setOnClickListener { typeCharacter(if(shiftOn)ch.uppercaseChar() else ch);if(shiftOn){shiftOn=false;findViewById<Button>(R.id.shiftButton).isSelected=false} }
            };row.addView(button)
        }
    }
    private fun matchParentHeight()=LinearLayout.LayoutParams.MATCH_PARENT
    private fun typeCharacter(char:Char){
        if (!acceptKeyboardTap()) return
        val sent=if(appMode==AppMode.PC)pc?.text(char.toString())==true else remote?.sendText(char.toString())==true
        if(!sent) show(if(appMode==AppMode.PC)"Najpierw połącz komputer" else "Najpierw połącz telewizor")
        else { keyboardBuffer.append(char);refreshKeyboardBuffer() }
    }
    private fun acceptKeyboardTap():Boolean {
        val now=android.os.SystemClock.elapsedRealtime()
        if(now-lastKeyboardTapAt<140L)return false
        lastKeyboardTapAt=now
        return true
    }
    private fun refreshKeyboardBuffer(){findViewById<TextView>(R.id.keyboardBuffer).text=keyboardBuffer.toString().ifEmpty{"Wpisywany tekst pojawi się tutaj"}}
    private fun setupBackspace(){
        val button=findViewById<Button>(R.id.backspaceButton)
        val clearAction=Runnable {
            val cleared=if(appMode==AppMode.PC)pc?.key("clear")==true else remote?.clearText()==true
            if(!cleared)show(if(appMode==AppMode.PC)"Najpierw połącz komputer" else "Najpierw połącz telewizor")
            else {backspaceHeld=true;keyboardBuffer.clear();refreshKeyboardBuffer();show("Pole wyczyszczone")}
        }
        button.setOnTouchListener { _,event ->
            when(event.actionMasked){
                MotionEvent.ACTION_DOWN->{
                    backspaceHeld=false
                    if((appMode==AppMode.TV&&remote==null)||(appMode==AppMode.PC&&pc==null))show(if(appMode==AppMode.PC)"Najpierw połącz komputer" else "Najpierw połącz telewizor") else handler.postDelayed(clearAction,3000)
                    true
                }
                MotionEvent.ACTION_UP->{handler.removeCallbacks(clearAction);if(!backspaceHeld){if(appMode==AppMode.PC)pc?.key("backspace")else remote?.backspace();deleteOneFromBuffer()};true}
                MotionEvent.ACTION_CANCEL->{handler.removeCallbacks(clearAction);true}
                else->true
            }
        }
    }
    private fun deleteOneFromBuffer(){if(keyboardBuffer.isNotEmpty()){keyboardBuffer.deleteCharAt(keyboardBuffer.length-1);refreshKeyboardBuffer()}}
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
    private fun startPcPairing(){
        if(address().isEmpty())return show("Wpisz adres IP komputera")
        val pin=code.text.toString().trim();if(pin.length!=6)return show("Wpisz 6-cyfrowy kod z programu Windows")
        busy(true);show("Parowanie z komputerem…")
        lifecycleScope.launch {
            val client=PcClient(address()){message->runOnUiThread{pc=null;show(message)}}
            val result=withContext(Dispatchers.IO){client.pair(pin,android.os.Build.MODEL)}
            busy(false);result.onSuccess { token ->
                getSharedPreferences("pc",MODE_PRIVATE).edit().putString("host",address()).putString("token",token).apply()
                show("Komputer sparowany. Łączenie…");connectPc()
            }.onFailure { show("Błąd parowania PC: ${it.message}") }
        }
    }
    private fun connect() {
        if(appMode==AppMode.PC)return connectPc()
        if (address().isEmpty()) return show("Wpisz adres IP telewizora")
        remote?.close(); remote = null; show("Łączenie z telewizorem…")
        lifecycleScope.launch {
            val client = TvRemote(this@MainActivity, address(), imeEvent={ imeState ->
                runOnUiThread {
                    val source=imeState.label.ifBlank { imeState.appPackage.substringAfterLast('.').ifBlank { "pole tekstowe TV" } }
                    show("Klawiatura TV aktywna: $source")
                }
            }) {
                runOnUiThread { remote = null; show("$it — po powrocie połączę ponownie") }
            }
            val result = withContext(Dispatchers.IO) { client.connect() }
            result.onSuccess {
                client.setTextMode(textMode);remote = client; saveHost(); setupPanel.visibility = View.GONE
                remotePanel.visibility = View.VISIBLE; show("Połączono — pilot jest gotowy")
            }
                .onFailure { client.close(); show(errorText(it)) }
        }
    }
    private fun connectPc(){
        if(address().isEmpty())return show("Wpisz adres IP komputera")
        val token=getSharedPreferences("pc",MODE_PRIVATE).getString("token",null)?:return show("Najpierw sparuj komputer kodem z odbiornika")
        pc?.close();pc=null;show("Łączenie z komputerem…")
        lifecycleScope.launch {
            val client=PcClient(address()){message->runOnUiThread{pc=null;show(message)}}
            val result=withContext(Dispatchers.IO){client.connect(token)}
            result.onSuccess{pc=client;saveHost();setupPanel.visibility=View.GONE;remotePanel.visibility=View.VISIBLE;show("Połączono z komputerem — pilot jest gotowy")}
                .onFailure{client.close();show("Błąd połączenia PC: ${it.message}")}
        }
    }
    private fun saveHost() = getSharedPreferences(if(appMode==AppMode.TV)"tv" else "pc", MODE_PRIVATE).edit().putString("host", address()).apply()
    private fun key(id: Int, value: Int) = findViewById<Button>(id).setOnClickListener {
        if(appMode==AppMode.PC){
            val sent=when(value){TvKey.UP->pc?.key("up");TvKey.DOWN->pc?.key("down");TvKey.LEFT->pc?.key("left");TvKey.RIGHT->pc?.key("right");TvKey.OK->pc?.key("enter");TvKey.BACK->pc?.key("escape");TvKey.HOME->pc?.key("home");TvKey.VOL_DOWN->pc?.media("volume_down");TvKey.VOL_UP->pc?.media("volume_up");TvKey.MUTE->pc?.media("volume_mute");TvKey.PLAY_PAUSE->pc?.media("play_pause");TvKey.POWER->pc?.system("lock");else->false}
            if(sent!=true)show("Najpierw połącz komputer")
        }else if(remote?.sendKey(value)!=true)show("Najpierw połącz telewizor")
    }
    private fun busy(value: Boolean) { pairButton.isEnabled = !value; confirmButton.isEnabled = !value }
    private fun show(message: String) { status.text = message }
    private fun errorText(t: Throwable): String = when {
        t.message?.contains("refused", true) == true -> "TV odrzucił połączenie. Sprawdź IP i usługę Android TV Remote."
        t.message?.contains("timed out", true) == true -> "Brak odpowiedzi. Sprawdź IP i wspólną sieć Wi‑Fi."
        else -> "Błąd: ${t.message ?: "nie udało się połączyć"}"
    }
    override fun onDestroy() { pairing?.close(); remote?.close(); pc?.close(); super.onDestroy() }

    override fun onResume() {
        super.onResume()
        if (::host.isInitialized && remote == null && host.text.isNotBlank() &&
            getSharedPreferences("tv", MODE_PRIVATE).getBoolean("paired", false)) connect()
    }
}
