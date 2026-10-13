using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace ControlOneReceiver;

internal static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();
        Application.Run(new ReceiverForm());
    }
}

internal sealed class ReceiverForm : Form
{
    private readonly Label status=new(){AutoSize=true,ForeColor=Color.FromArgb(214,169,60),Font=new Font("Segoe UI",11)};
    private readonly Label ip=new(){AutoSize=true,ForeColor=Color.White,Font=new Font("Segoe UI",14,FontStyle.Bold)};
    private readonly Label pin=new(){AutoSize=true,ForeColor=Color.White,Font=new Font("Segoe UI",28,FontStyle.Bold)};
    private readonly CancellationTokenSource stop=new();
    private readonly string tokenFile=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),"Control One","tokens.txt");
    private string pairingCode=RandomNumberGenerator.GetInt32(100000,1000000).ToString();
    private readonly HashSet<string> tokens=new();

    public ReceiverForm()
    {
        Text="Control One Receiver";BackColor=Color.FromArgb(5,5,5);ClientSize=new Size(430,260);FormBorderStyle=FormBorderStyle.FixedSingle;MaximizeBox=false;
        var title=new Label{Text="CONTROL ONE",AutoSize=true,ForeColor=Color.White,Font=new Font("Segoe UI",22,FontStyle.Bold),Location=new Point(24,20)};
        var info=new Label{Text="Odbiornik Windows — port 45821",AutoSize=true,ForeColor=Color.Gray,Location=new Point(27,62)};
        ip.Location=new Point(27,95);pin.Location=new Point(27,138);status.Location=new Point(27,210);
        Controls.AddRange([title,info,ip,pin,status]);
        Directory.CreateDirectory(Path.GetDirectoryName(tokenFile)!);if(File.Exists(tokenFile))foreach(var t in File.ReadAllLines(tokenFile))tokens.Add(t.Trim());
        ip.Text="IP: "+LocalIp();pin.Text="Kod: "+pairingCode;status.Text="Uruchom Control One i wybierz tryb PC";
        FormClosed+=(_,_)=>stop.Cancel();_ = Task.Run(Listen);
    }

    private async Task Listen()
    {
        var server=new TcpListener(IPAddress.Any,45821);server.Start();
        try{while(!stop.IsCancellationRequested){var client=await server.AcceptTcpClientAsync(stop.Token);_ = Task.Run(()=>Handle(client));}}
        catch(OperationCanceledException){} finally{server.Stop();}
    }
    private async Task Handle(TcpClient client)
    {
        using(client)
        using(var reader=new StreamReader(client.GetStream(),Encoding.UTF8))
        using(var writer=new StreamWriter(client.GetStream(),new UTF8Encoding(false)){AutoFlush=true})
        try{
            var first=JsonDocument.Parse((await reader.ReadLineAsync())??"").RootElement;
            var type=first.GetProperty("type").GetString();
            if(type=="pair"){
                if(first.GetProperty("code").GetString()!=pairingCode){await Reply(writer,false,"Nieprawidłowy kod");return;}
                var token=Convert.ToBase64String(RandomNumberGenerator.GetBytes(32));tokens.Add(token);File.WriteAllLines(tokenFile,tokens);await writer.WriteLineAsync(JsonSerializer.Serialize(new{ok=true,token}));
                pairingCode=RandomNumberGenerator.GetInt32(100000,1000000).ToString();BeginInvoke((Action)(()=>{pin.Text="Kod: "+pairingCode;status.Text="Telefon został sparowany";}));return;
            }
            if(type!="auth"||!tokens.Contains(first.GetProperty("token").GetString()??"")){await Reply(writer,false,"Najpierw sparuj telefon");return;}
            await Reply(writer,true,null);BeginInvoke((Action)(()=>status.Text="Telefon połączony"));
            string? line;while((line=await reader.ReadLineAsync())!=null)InputController.Execute(JsonDocument.Parse(line).RootElement);
        }catch(Exception ex){BeginInvoke((Action)(()=>status.Text="Błąd: "+ex.Message));}
        finally{BeginInvoke((Action)(()=>status.Text="Oczekiwanie na telefon"));}
    }
    private static Task Reply(StreamWriter w,bool ok,string? error)=>w.WriteLineAsync(JsonSerializer.Serialize(new{ok,error}));
    private static string LocalIp()=>NetworkInterface.GetAllNetworkInterfaces().Where(n=>n.OperationalStatus==OperationalStatus.Up).SelectMany(n=>n.GetIPProperties().UnicastAddresses).FirstOrDefault(a=>a.Address.AddressFamily==AddressFamily.InterNetwork&&!IPAddress.IsLoopback(a.Address))?.Address.ToString()??"nie znaleziono";
}

internal static class InputController
{
    const uint INPUT_MOUSE=0,INPUT_KEYBOARD=1,KEYUP=2,UNICODE=4;
    [StructLayout(LayoutKind.Sequential)] struct INPUT{public uint type;public InputUnion u;}
    [StructLayout(LayoutKind.Explicit)] struct InputUnion{[FieldOffset(0)]public MOUSEINPUT mi;[FieldOffset(0)]public KEYBDINPUT ki;}
    [StructLayout(LayoutKind.Sequential)] struct MOUSEINPUT{public int dx,dy;public uint mouseData,dwFlags,time;public nint extra;}
    [StructLayout(LayoutKind.Sequential)] struct KEYBDINPUT{public ushort vk,scan;public uint flags,time;public nint extra;}
    [DllImport("user32.dll")]static extern uint SendInput(uint n,INPUT[] p,int size);
    [DllImport("user32.dll")]static extern bool LockWorkStation();
    public static void Execute(JsonElement m){var t=m.GetProperty("type").GetString();switch(t){case"mouse":Mouse(m.GetProperty("dx").GetInt32(),m.GetProperty("dy").GetInt32());break;case"click":Click(m.GetProperty("button").GetString()??"left");break;case"key":HandleKey(m.GetProperty("key").GetString()??"");break;case"text":Text(m.GetProperty("value").GetString()??"");break;case"media":Media(m.GetProperty("action").GetString()??"");break;case"system":if(m.GetProperty("action").GetString()=="lock")LockWorkStation();break;}}
    static void Mouse(int x,int y)=>Send([new(){type=INPUT_MOUSE,u=new(){mi=new(){dx=x,dy=y,dwFlags=1}}}]);
    static void Click(string b){uint d=b=="right"?8u:2u,u=b=="right"?16u:4u;Send([new(){type=INPUT_MOUSE,u=new(){mi=new(){dwFlags=d}}},new(){type=INPUT_MOUSE,u=new(){mi=new(){dwFlags=u}}}]);}
    static ushort Name(string n)=>n switch{"up"=>0x26,"down"=>0x28,"left"=>0x25,"right"=>0x27,"enter"=>0x0D,"escape"=>0x1B,"home"=>0x24,"backspace"=>0x08,_=>0};
    static void HandleKey(string n){if(n=="clear"){Send([K(0x11,0),K(0x41,0),K(0x41,KEYUP),K(0x11,KEYUP)]);Key(0x08);}else Key(Name(n));}
    static void Key(ushort v){if(v==0)return;Send([K(v,0),K(v,KEYUP)]);}
    static void Text(string s){foreach(var c in s)Send([U(c,0),U(c,KEYUP)]);}
    static void Media(string a)=>Key(a switch{"volume_up"=>0xAF,"volume_down"=>0xAE,"volume_mute"=>0xAD,"play_pause"=>0xB3,_=>0});
    static INPUT K(ushort v,uint f)=>new(){type=INPUT_KEYBOARD,u=new(){ki=new(){vk=v,flags=f}}};
    static INPUT U(char c,uint f)=>new(){type=INPUT_KEYBOARD,u=new(){ki=new(){scan=c,flags=UNICODE|f}}};
    static void Send(INPUT[] a)=>SendInput((uint)a.Length,a,Marshal.SizeOf<INPUT>());
}
