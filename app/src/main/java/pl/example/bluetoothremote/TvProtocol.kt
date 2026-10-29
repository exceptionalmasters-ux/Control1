package pl.example.bluetoothremote

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

object TvKey { const val HOME=3; const val BACK=4; const val UP=19; const val DOWN=20; const val LEFT=21; const val RIGHT=22; const val OK=23; const val VOL_UP=24; const val VOL_DOWN=25; const val POWER=26; const val PLAY_PAUSE=85; const val ENTER=66; const val DEL=67; const val SHIFT=59; const val CTRL=113; const val MUTE=164; const val CHANNEL_UP=166; const val CHANNEL_DOWN=167; fun digit(value:Int)=7+value }

private sealed interface PVal { data class N(val v:Long):PVal; data class B(val v:ByteArray):PVal }
private class PWriter {
    private val out=ByteArrayOutputStream()
    fun number(f:Int,v:Long)=apply{ tag(f,0); varint(out,v) }
    fun number(f:Int,v:Int)=number(f,v.toLong())
    fun bytes(f:Int,v:ByteArray)=apply{ tag(f,2); varint(out,v.size.toLong()); out.write(v) }
    fun string(f:Int,v:String)=bytes(f,v.toByteArray())
    fun message(f:Int,build:PWriter.()->Unit)=bytes(f,PWriter().apply(build).data())
    fun data()=out.toByteArray()
    private fun tag(f:Int,w:Int)=varint(out,((f shl 3) or w).toLong())
}
private fun varint(out:OutputStream,value:Long){ var v=value; while(true){ val b=(v and 127).toInt(); v=v ushr 7; if(v==0L){out.write(b);return};out.write(b or 128)} }
private fun OutputStream.frame(data:ByteArray){varint(this,data.size.toLong());write(data);flush()}
private fun InputStream.frame():ByteArray{var n=0L;var s=0;while(true){val b=read();if(b<0)throw EOFException("Połączenie zamknięte");n=n or ((b and 127).toLong() shl s);if(b and 128==0)break;s+=7};val data=ByteArray(n.toInt());var p=0;while(p<data.size){val r=read(data,p,data.size-p);if(r<0)throw EOFException("Połączenie zamknięte");p+=r};return data}
private fun parse(data:ByteArray):Map<Int,List<PVal>>{val result=mutableMapOf<Int,MutableList<PVal>>();var p=0;fun vi():Long{var r=0L;var s=0;while(true){if(p>=data.size)throw EOFException();val b=data[p++].toInt() and 255;r=r or ((b and 127).toLong() shl s);if(b and 128==0)return r;s+=7}};while(p<data.size){val t=vi();val f=(t ushr 3).toInt();val v=when((t and 7).toInt()){0->PVal.N(vi());2->{val n=vi().toInt();PVal.B(data.copyOfRange(p,p+n)).also{p+=n}};1->{p+=8;continue};5->{p+=4;continue};else->error("Nieznany komunikat TV")};result.getOrPut(f){mutableListOf()}.add(v)};return result}
private fun Map<Int,List<PVal>>.has(f:Int)=containsKey(f)
private fun Map<Int,List<PVal>>.num(f:Int)=((get(f)?.firstOrNull()) as? PVal.N)?.v
private fun Map<Int,List<PVal>>.msg(f:Int)=((get(f)?.firstOrNull()) as? PVal.B)?.v?.let(::parse)
private fun Map<Int,List<PVal>>.str(f:Int)=((get(f)?.firstOrNull()) as? PVal.B)?.v?.toString(Charsets.UTF_8)

private data class Identity(val store:KeyStore,val cert:X509Certificate)
private object IdentityStore {
    private val password="control1".toCharArray(); private const val alias="remote"
    @Synchronized fun get(context:Context):Identity{val file=File(context.filesDir,"remote.p12");if(file.exists())runCatching{return load(file)};return create().also{file.outputStream().use{stream->it.store.store(stream,password)}}}
    private fun load(file:File):Identity{val ks=KeyStore.getInstance("PKCS12");file.inputStream().use{ks.load(it,password)};return Identity(ks,ks.getCertificate(alias) as X509Certificate)}
    private fun create():Identity{val pair=KeyPairGenerator.getInstance("RSA").apply{initialize(2048)}.generateKeyPair();val now=System.currentTimeMillis();val name=X500Name("CN=Control1 Android TV Remote");val builder=JcaX509v3CertificateBuilder(name,BigInteger.valueOf(now),Date(now-86400000),Date(now+630720000000L),name,pair.public).addExtension(Extension.basicConstraints,false,BasicConstraints(false));val cert=JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private)));val ks=KeyStore.getInstance("PKCS12").apply{load(null,null);setKeyEntry(alias,pair.private,password,arrayOf(cert))};return Identity(ks,cert)}
    fun password()=password
}
private class Trust:X509TrustManager{var peer:X509Certificate?=null;override fun checkClientTrusted(c:Array<X509Certificate>,a:String){};override fun checkServerTrusted(c:Array<X509Certificate>,a:String){peer=c.firstOrNull()};override fun getAcceptedIssuers()=emptyArray<X509Certificate>()}
private data class SecureConnection(val socket:SSLSocket,val peer:X509Certificate)
private fun tls(host:String,port:Int,id:Identity,readTimeout:Int):SecureConnection{val km=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply{init(id.store,IdentityStore.password())}.keyManagers;val trust=Trust();val ctx=SSLContext.getInstance("TLS").apply{init(km,arrayOf(trust),SecureRandom())};val socket=ctx.socketFactory.createSocket() as SSLSocket;socket.connect(InetSocketAddress(host,port),6000);socket.soTimeout=readTimeout;socket.startHandshake();return SecureConnection(socket,trust.peer ?: socket.session.peerCertificates[0] as X509Certificate)}

class TvPairing(context:Context,private val host:String):Closeable{
    private val id=IdentityStore.get(context);private var connection:SecureConnection?=null
    fun start(name:String): Result<Unit> = runCatching{val c=tls(host,6467,id,10000);connection=c;val input=c.socket.inputStream;val output=c.socket.outputStream;output.frame(outer{message(10){string(1,"Control1");string(2,name)}});while(true){val m=parse(input.frame());status(m);when{m.has(11)->output.frame(outer{message(20){message(1){number(1,3);number(2,6)};number(3,1)}});m.has(20)->output.frame(outer{message(30){message(1){number(1,3);number(2,6)};number(2,1)}});m.has(31)->return@runCatching;else->error("Nieoczekiwana odpowiedź podczas parowania")}}}.onFailure{close()}
    fun complete(raw:String):Result<Unit>{val code=raw.trim().uppercase();val digest=runCatching{digest(code)}.getOrElse{return Result.failure(it)};return runCatching{val c=connection?:error("Sesja parowania wygasła");c.socket.outputStream.frame(outer{message(40){bytes(1,digest)}});val reply=parse(c.socket.inputStream.frame());status(reply);if(!reply.has(41))error("TV odrzucił kod")}.also{close()}}
    private fun digest(code:String):ByteArray {
        require(code.length == 6 && code.all { it.isDigit() || it in 'A'..'F' }) { "Kod musi mieć 6 znaków 0-9/A-F" }
        val c = connection ?: error("Sesja wygasła")
        val client = id.cert.publicKey as RSAPublicKey
        val server = c.peer.publicKey as RSAPublicKey
        fun BigInteger.raw() = toByteArray().let { if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
        val d = MessageDigest.getInstance("SHA-256").apply {
            update(client.modulus.raw()); update(client.publicExponent.raw())
            update(server.modulus.raw()); update(server.publicExponent.raw())
            update(byteArrayOf(code.substring(2,4).toInt(16).toByte(), code.substring(4,6).toInt(16).toByte()))
        }.digest()
        require((d[0].toInt() and 255) == code.substring(0,2).toInt(16)) { "Kod nie pasuje do tego telewizora" }
        return d
    }
    private fun status(m:Map<Int,List<PVal>>){val s=m.num(2)?:200;if(s!=200L)error("TV odrzucił parowanie (kod $s)")}
    private fun outer(build:PWriter.()->Unit)=PWriter().number(1,2).number(2,200).apply(build).data()
    override fun close(){runCatching{connection?.socket?.close()};connection=null}
}

data class ImeState(val appPackage:String,val value:String,val label:String,val cursorStart:Int,val cursorEnd:Int)
enum class TextMode { AUTO, IME, LEGACY }

class TvRemote(context:Context,private val host:String,private val imeEvent:(ImeState)->Unit={},private val event:(String)->Unit):Closeable{
    private val id=IdentityStore.get(context);private var connection:SecureConnection?=null;private var output:OutputStream?=null;private var scope:CoroutineScope?=null;private val writer=Executors.newSingleThreadExecutor();@Volatile private var ready=false;@Volatile private var batchIme=0;@Volatile private var batchField=0;@Volatile private var appIme=0;@Volatile private var statusField=0;@Volatile private var imeFieldActive=false;@Volatile private var imeValue="";@Volatile private var appPackage="";@Volatile private var textMode=TextMode.AUTO;private var features=0
    fun connect(): Result<Unit> = runCatching{
        DiagnosticLog.append("TvRemote.connect TLS start host=$host")
        val c=tls(host,6466,id,10000);connection=c;output=c.socket.outputStream;val input=c.socket.inputStream
        while(!ready)handle(parse(input.frame()))
        DiagnosticLog.append("TvRemote.connect ready features=$features")
        c.socket.soTimeout=0
        scope=CoroutineScope(SupervisorJob()+Dispatchers.IO).also{it.launch{
            runCatching{while(true)handle(parse(input.frame()))}.onFailure{
                DiagnosticLog.append("TvRemote.reader failure ready=$ready: ${it.stackTraceToString()}")
                if(ready){ready=false;event("Utracono połączenie z TV")}
            }
        }}
    }.onFailure{DiagnosticLog.append("TvRemote.connect failure: ${it.stackTraceToString()}");close()}
    private fun handle(m:Map<Int,List<PVal>>){
        DiagnosticLog.append("TvRemote.receive fields=${m.keys.sorted()}")
        when{
        m.has(1)->{features=(m.msg(1)?.num(1)?.toInt()?:0) and 615;send{message(1){number(1,features);message(2){string(1,android.os.Build.MODEL);string(2,android.os.Build.MANUFACTURER);number(3,1);string(4,"1");string(5,"pl.example.bluetoothremote");string(6,"0.13")}}}}
        m.has(2)->send{message(2){number(1,features)}}
        m.has(8)->send{message(9){number(1,m.msg(8)?.num(1)?:0)}}
        m.has(20)->{
            val message=m.msg(20);val app=message?.msg(1);val status=message?.msg(2)
            app?.num(1)?.toInt()?.let{appIme=it};app?.str(12)?.takeIf{it.isNotEmpty()}?.let{appPackage=it}
            if(status!=null){status.num(1)?.toInt()?.let{statusField=it};DiagnosticLog.append("IME appStatus active app=$appPackage appIme=$appIme field=$statusField");publishIme(appPackage,status)}
            else {imeFieldActive=false;DiagnosticLog.append("IME appStatus inactive app=$appPackage appIme=$appIme")}
        }
        m.has(21)->{val edit=m.msg(21);edit?.num(1)?.toInt()?.let{batchIme=it};edit?.num(2)?.toInt()?.let{batchField=it};DiagnosticLog.append("IME batch counters ime=$batchIme field=$batchField")}
        m.has(22)->{val status=m.msg(22)?.msg(2);status?.num(1)?.toInt()?.let{statusField=it};DiagnosticLog.append("IME status update present=${status != null} field=$statusField");publishIme(appPackage,status)}
        m.has(40)->{ready=true;DiagnosticLog.append("TvRemote handshake ready")}
    }}
    private fun publishIme(app:String,status:Map<Int,List<PVal>>?){
        imeFieldActive=status!=null;imeValue=status?.str(2).orEmpty()
        val state=ImeState(app,status?.str(2).orEmpty(),status?.str(6).orEmpty(),status?.num(3)?.toInt()?:0,status?.num(4)?.toInt()?:0)
        imeEvent(state)
    }
    fun sendKey(key:Int, direction:Int = 3):Boolean{if(!ready)return false;send{message(10){number(1,key);number(2,direction)}};return true}
    fun sendText(text:String):Boolean{
        if(!ready||text.isEmpty())return false
        val caret=(text.length-1).coerceAtLeast(0)
        DiagnosticLog.append("TvRemote.sendText length=${text.length} caret=$caret imeMode=${useImeMode()} active=$imeFieldActive app=$appPackage appIme=$appIme statusField=$statusField batchIme=$batchIme batchField=$batchField")
        sendImeEdit(caret,caret,text,useImeMode())
        return true
    }
    fun setTextMode(mode:TextMode){textMode=mode}
    private fun useImeMode()=when(textMode){TextMode.IME->true;TextMode.LEGACY->false;TextMode.AUTO->!appPackage.contains("youtube",true)&&imeFieldActive}
    private fun sendImeEdit(start:Int,end:Int,text:String,imeMode:Boolean){
        val imeCounter=if(imeMode)appIme else batchIme;val fieldCounter=if(imeMode)statusField else batchField
        DiagnosticLog.append("TvRemote.sendImeEdit imeMode=$imeMode ime=$imeCounter field=$fieldCounter range=$start..$end length=${text.length}")
        send{message(21){number(1,imeCounter);number(2,fieldCounter);message(3){number(1,1);message(2){number(1,start);number(2,end);string(3,text)}}}}
    }
    fun sendTextAsKeys(text:String):Int {
        if(!ready)return text.length
        var skipped=0
        text.forEach { char ->
            val lower=char.lowercaseChar()
            val code=when(lower){in 'a'..'z'->29+(lower-'a');in '0'..'9'->7+(lower-'0');' '->62;','->55;'.'->56;'-'->69;'='->70;'/'->76;'\''->75;';'->74;'['->71;']'->72;else->null}
            if(code==null){skipped++}else if(char.isUpperCase()){sendKey(TvKey.SHIFT,1);sendKey(code);sendKey(TvKey.SHIFT,2)}else sendKey(code)
        }
        return skipped
    }
    fun sendCharacter(char:Char, shifted:Boolean=false):Boolean {
        if(!ready)return false
        val lower=char.lowercaseChar()
        val code=when(lower){in 'a'..'z'->29+(lower-'a');in '0'..'9'->7+(lower-'0');' '->62;','->55;'.'->56;'-'->69;'='->70;'/'->76;'\''->75;';'->74;'['->71;']'->72;else->null}?:return false
        if(shifted||char.isUpperCase()){sendKey(TvKey.SHIFT,1);sendKey(code);sendKey(TvKey.SHIFT,2)}else sendKey(code)
        return true
    }
    fun backspace():Boolean{
        if(!ready)return false
        if(useImeMode()&&imeFieldActive&&imeValue.isNotEmpty()){val end=imeValue.length;sendImeEdit(end-1,end,"",true);imeValue=imeValue.dropLast(1);return true}
        return sendKey(TvKey.DEL)
    }
    fun clearText():Boolean {
        if(!ready)return false
        if(useImeMode()&&imeFieldActive&&imeValue.isNotEmpty()){sendImeEdit(0,imeValue.length,"",true);imeValue="";return true}
        sendKey(TvKey.CTRL,1);sendKey(29);sendKey(TvKey.CTRL,2);sendKey(TvKey.DEL)
        return true
    }
    fun clearByHoldingBackspace():Boolean {
        if(!ready)return false
        sendKey(TvKey.DEL,1)
        return true
    }
    fun openUrl(url:String):Boolean {
        if(!ready)return false
        send{message(90){string(1,url)}}
        return true
    }
    private fun send(build:PWriter.()->Unit){
        val data=PWriter().apply(build).data();val stream=output?:return
        runCatching{writer.execute{
            runCatching{stream.frame(data)}.onFailure{
                DiagnosticLog.append("TvRemote.send failure: ${it.stackTraceToString()}")
                ready=false;event("Błąd wysyłania do TV")
            }
        }}.onFailure{DiagnosticLog.append("TvRemote.writer rejected: ${it.stackTraceToString()}")}
    }
    override fun close(){
        DiagnosticLog.append("TvRemote.close ready=$ready")
        ready=false;scope?.cancel();scope=null;runCatching{connection?.socket?.close()};connection=null;output=null;writer.shutdownNow()
    }
}
