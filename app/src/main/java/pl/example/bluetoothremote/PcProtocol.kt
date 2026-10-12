package pl.example.bluetoothremote

import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

class PcClient(private val host:String,private val event:(String)->Unit):Closeable {
    companion object { const val PORT=45821 }
    private var socket:Socket?=null
    private var input:BufferedReader?=null
    private var output:BufferedWriter?=null
    private val writer=Executors.newSingleThreadExecutor()
    @Volatile private var ready=false

    fun pair(code:String,device:String):Result<String> = runCatching {
        open()
        sendNow(JSONObject().put("type","pair").put("code",code).put("device",device))
        val reply=JSONObject(input?.readLine()?:error("Komputer zamknął połączenie"))
        if(!reply.optBoolean("ok"))error(reply.optString("error","Nieprawidłowy kod"))
        reply.getString("token")
    }.also { close() }

    fun connect(token:String):Result<Unit> = runCatching {
        open();sendNow(JSONObject().put("type","auth").put("token",token))
        val reply=JSONObject(input?.readLine()?:error("Komputer zamknął połączenie"))
        if(!reply.optBoolean("ok"))error(reply.optString("error","Komputer odrzucił połączenie"))
        ready=true
    }.onFailure { close() }

    private fun open(){
        val s=Socket();s.connect(InetSocketAddress(host,PORT),6000);s.tcpNoDelay=true
        socket=s;input=BufferedReader(InputStreamReader(s.getInputStream()));output=BufferedWriter(OutputStreamWriter(s.getOutputStream()))
    }
    private fun sendNow(message:JSONObject){output?.apply{write(message.toString());newLine();flush()}?:error("Brak połączenia")}
    private fun send(message:JSONObject):Boolean {
        if(!ready)return false
        writer.execute{runCatching{sendNow(message)}.onFailure{ready=false;event("Utracono połączenie z komputerem")}}
        return true
    }
    fun mouse(dx:Int,dy:Int)=send(JSONObject().put("type","mouse").put("dx",dx).put("dy",dy))
    fun click(button:String="left")=send(JSONObject().put("type","click").put("button",button))
    fun key(name:String)=send(JSONObject().put("type","key").put("key",name))
    fun text(value:String)=send(JSONObject().put("type","text").put("value",value))
    fun media(action:String)=send(JSONObject().put("type","media").put("action",action))
    fun system(action:String)=send(JSONObject().put("type","system").put("action",action))
    override fun close(){ready=false;runCatching{socket?.close()};socket=null;input=null;output=null}
}
