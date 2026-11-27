package wiiuu.xcloud;

import com.google.gson.*;
import dev.onvoid.webrtc.*;
import dev.onvoid.webrtc.client.swing.VideoView;
import dev.onvoid.webrtc.media.MediaType;
import dev.onvoid.webrtc.media.video.VideoTrack;
import dev.onvoid.webrtc.media.video.VideoTrackSink;
import dev.onvoid.webrtc.media.video.VideoFrameAccess;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Browser-free JVM media process. Xbox account grants remain in the loopback service. */
public final class NativeRtcPlayer {
    private static final Gson JSON = new Gson();
    private static final int WIDTH=1280, HEIGHT=720, FPS=30;
    private static final String[] BUTTONS={"Nexus","Menu","View","A","B","X","Y","DPadUp","DPadDown","DPadLeft","DPadRight","LeftShoulder","RightShoulder","LeftThumb","RightThumb"};
    private static final Set<String> TERMINAL=Set.of("Failed","Stopped","Closed","Ended","Terminated","Cancelled");
    private final AtomicBoolean closing=new AtomicBoolean();
    private final BlockingQueue<String> messages=new ArrayBlockingQueue<>(64);
    private final List<Map<String,Object>> candidates=new CopyOnWriteArrayList<>();
    private final Map<String,RTCDataChannel> channels=new LinkedHashMap<>();
    private final Set<Integer> activePads=new TreeSet<>();
    private final long epoch=System.nanoTime();
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final String secret,title;
    private final URI endpoint;
    private JFrame window;
    private JLabel status;
    private VideoView view;
    private PeerConnectionFactory factory;
    private RTCPeerConnection peer;
    private volatile boolean muted;
    private boolean started, handshake, inputOpened, controlOpened, messageOpened;
    private int sequence;
    private volatile VideoTrack video;
    private VideoTrackSink sink;
    private final java.util.function.Supplier<List<Map<String,Object>>> controllerFrames;
    private NativeRtcPlayer(int port,String secret,String title,boolean audio,java.util.function.Supplier<List<Map<String,Object>>> controllerFrames) {
        this.secret=secret;
        this.title=title;
        this.controllerFrames=Objects.requireNonNull(controllerFrames);
        if(port<1||port>65535||secret==null||!secret.matches("[a-f0-9]{64}")||title==null)throw new IllegalArgumentException("Invalid launcher");
        endpoint=URI.create("http://127.0.0.1:"+port+"/api/");
        muted=!audio;
    }
    static Map<String,Object> obj(Object... fields){
        Map<String,Object> out=new LinkedHashMap<>();
        for(int i=0;i<fields.length;i+=2)out.put((String)fields[i],fields[i+1]);
        return out;
    }
    private static JsonObject object(JsonElement x){return x!=null&&x.isJsonObject()?x.getAsJsonObject():new JsonObject();}
    private static String string(JsonObject x,String key){JsonElement v=x.get(key);return v!=null&&v.isJsonPrimitive()?v.getAsString():"";}
    private static double number(JsonObject x,String key){try{double n=x.get(key).getAsDouble();return Double.isFinite(n)?n:0;}catch(Exception e){return 0;}}
    private static JsonElement unwrap(JsonElement x){String s=string(object(x),"exchangeResponse");return s.isEmpty()?x:JsonParser.parseString(s);}
    private JsonElement request(String action,Map<String,Object> data,int seconds)throws Exception{
        HttpRequest.Builder b=HttpRequest.newBuilder(endpoint.resolve(action)).timeout(Duration.ofSeconds(seconds)).header("X-Wiiuu-Session",secret);
        if(data==null)b.GET();else b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(data)));
        HttpResponse<String> r=http.send(b.build(),HttpResponse.BodyHandlers.ofString());
        if(r.statusCode()!=200)throw new IOException("Signaling HTTP "+r.statusCode());
        return JsonParser.parseString(r.body());
    }
    private JsonObject state()throws Exception{return object(request("state",null,15));}
    private void label(String text){SwingUtilities.invokeLater(()->status.setText(text));}
    private void ui()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            window=new JFrame("WII-UU Xbox Cloud Player");
            window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            window.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){closing.set(true);}});
            view=new VideoView();
            status=new JLabel("Preparing Java RTC...",SwingConstants.CENTER);
            JButton stop=new JButton("Disconnect");stop.addActionListener(e->closing.set(true));
            JButton audio=new JButton(muted?"Enable audio":"Mute audio");
            audio.addActionListener(e->{muted=!muted;audio.setText(muted?"Enable audio":"Mute audio");});
            JPanel bar=new JPanel(new BorderLayout());bar.add(status,BorderLayout.CENTER);bar.add(stop,BorderLayout.EAST);bar.add(audio,BorderLayout.WEST);
            window.add(view,BorderLayout.CENTER);window.add(bar,BorderLayout.SOUTH);
            window.getRootPane().registerKeyboardAction(e->closing.set(true),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
            window.setSize(WIDTH,HEIGHT);window.setLocationRelativeTo(null);window.setVisible(true);
        });
    }
    private void setup(){
        factory=new PeerConnectionFactory();
        peer=factory.createPeerConnection(new RTCConfiguration(),new PeerConnectionObserver(){
            public void onIceCandidate(RTCIceCandidate c){
                if(c!=null&&c.sdp!=null&&!c.sdp.isBlank())candidates.add(obj("candidate",c.sdp,"sdpMid",c.sdpMid,"sdpMLineIndex",c.sdpMLineIndex));
            }
            public void onConnectionChange(RTCPeerConnectionState s){
                if(s==RTCPeerConnectionState.FAILED||s==RTCPeerConnectionState.CLOSED)closing.set(true);
            }
            public void onTrack(RTCRtpTransceiver t){
                if(t.getReceiver().getTrack() instanceof VideoTrack v && video==null){
                    video=v;
                    sink=frame->{try{if(!closing.get())view.accept(frame);}finally{frame.release();}};
                    v.addSink(sink,VideoFrameAccess.SHARED);
                }
            }
        });
        if(peer==null)throw new IllegalStateException("Peer creation failed");
        peer.setAudioRecording(false);
        peer.setAudioPlayout(!muted);
        RTCRtpTransceiverInit recv=new RTCRtpTransceiverInit();recv.direction=RTCRtpTransceiverDirection.RECV_ONLY;
        RTCRtpTransceiver vt=peer.addTransceiver(MediaType.VIDEO,recv);
        List<RTCRtpCodecCapability> h264=factory.getRtpReceiverCapabilities(MediaType.VIDEO).getCodecs().stream().filter(c->c.getName().equalsIgnoreCase("H264")||c.getName().equalsIgnoreCase("rtx")).toList();
        if(h264.stream().noneMatch(c->c.getName().equalsIgnoreCase("H264")))throw new IllegalStateException("H264 decoder missing");
        vt.setCodecPreferences(h264);
        RTCRtpTransceiver at=peer.addTransceiver(MediaType.AUDIO,recv);
        at.setCodecPreferences(factory.getRtpReceiverCapabilities(MediaType.AUDIO).getCodecs().stream().filter(c->c.getName().equalsIgnoreCase("opus")).toList());
        String[][] specs={{"control","controlV1"},{"input","1.0"},{"message","messageV1"},{"chat","chatV1"}};
        for(String[] spec:specs){
            RTCDataChannelInit init=new RTCDataChannelInit();init.protocol=spec[1];
            if(spec[0].equals("input")){init.ordered=false;init.maxRetransmits=0;}
            RTCDataChannel ch=peer.createDataChannel(spec[0],init);channels.put(spec[0],ch);
            if(spec[0].equals("message"))ch.registerObserver(new RTCDataChannelObserver(){
                public void onBufferedAmountChange(long n){}
                public void onStateChange(){}
                public void onMessage(RTCDataChannelBuffer b){if(!b.binary&&b.data.remaining()<=65536)messages.offer(StandardCharsets.UTF_8.decode(b.data.duplicate()).toString());}
            });
        }
    }
    private void check()throws InterruptedException{if(closing.get())throw new InterruptedException("Closed");}
    private void pause(long ms)throws InterruptedException{check();Thread.sleep(ms);check();}
    private RTCSessionDescription offer()throws Exception{
        CompletableFuture<RTCSessionDescription> f=new CompletableFuture<>();
        peer.createOffer(new RTCOfferOptions(),new CreateSessionDescriptionObserver(){
            public void onSuccess(RTCSessionDescription s){f.complete(s);}
            public void onFailure(String e){f.completeExceptionally(new IOException("Offer failed"));}
        });
        return f.get(20,TimeUnit.SECONDS);
    }
    private void description(RTCSessionDescription s,boolean local)throws Exception{
        CompletableFuture<Void> f=new CompletableFuture<>();
        SetSessionDescriptionObserver observer=new SetSessionDescriptionObserver(){
            public void onSuccess(){f.complete(null);}
            public void onFailure(String e){f.completeExceptionally(new IOException("Description rejected"));}
        };
        if(local)peer.setLocalDescription(s,observer);else peer.setRemoteDescription(s,observer);
        f.get(20,TimeUnit.SECONDS);
    }
    static List<JsonObject> remoteIce(JsonElement value){
        value=unwrap(value);
        if(value.isJsonObject()){JsonObject o=value.getAsJsonObject();value=o.has("iceCandidates")?o.get("iceCandidates"):o.get("candidates");}
        List<JsonObject> out=new ArrayList<>();
        if(value!=null&&value.isJsonArray())for(JsonElement e:value.getAsJsonArray()){
            JsonObject o=object(e);String c=string(o,"candidate").trim();
            if(c.startsWith("a="))c=c.substring(2);
            if(c.startsWith("candidate:")&&(!string(o,"sdpMid").isBlank()||o.has("sdpMLineIndex")&&!o.get("sdpMLineIndex").isJsonNull())){
                o=o.deepCopy();o.addProperty("candidate",c);out.add(o);
            }
        }
        return out;
    }
    private void negotiate()throws Exception{
        request("start",obj("titleId",title),90);started=true;
        label("Waiting for Xbox server...");
        boolean transferred=false,ready=false;
        for(int i=0;i<240;i++){check();String s=string(state(),"state");
            if(s.equals("Provisioned")){ready=true;break;}
            if(TERMINAL.contains(s))throw new IOException("Session ended");
            if(s.equals("ReadyToConnect")&&!transferred){request("connect",Map.of(),35);transferred=true;}
            pause(1000);
        }
        if(!ready)throw new IOException("Provisioning timed out");
        label("Negotiating Java WebRTC...");
        setup();
        RTCSessionDescription offer=offer();description(offer,true);
        request("sdpPost",obj("sdp",offer.sdp),35);
        String answer="";
        for(int i=0;i<60;i++){check();answer=string(object(unwrap(request("sdpGet",null,15))),"sdp");if(!answer.isBlank())break;pause(500);}
        if(answer.isBlank())throw new IOException("No SDP answer");
        description(new RTCSessionDescription(RTCSdpType.ANSWER,answer),false);
        for(int i=0;i<80&&peer.getIceGatheringState()!=RTCIceGatheringState.COMPLETE;i++)pause(100);
        if(candidates.isEmpty())throw new IOException("No local ICE candidates");
        request("icePost",obj("candidates",List.copyOf(candidates)),35);
        boolean gotIce=false;
        for(int i=0;i<60;i++){
            check();List<JsonObject> remote=remoteIce(request("iceGet",null,15));
            if(!remote.isEmpty()||answer.contains("a=candidate:")){
                for(JsonObject c:remote){String mid=string(c,"sdpMid");int index=c.has("sdpMLineIndex")&&!c.get("sdpMLineIndex").isJsonNull()?(int)number(c,"sdpMLineIndex"):-1;
                    if(index<0&&!mid.isBlank()){String[] lines=answer.split("\\r?\\n");int n=-1;for(String line:lines){if(line.startsWith("m="))n++;if(line.equals("a=mid:"+mid)){index=n;break;}}}
                    if(index>=0)peer.addIceCandidate(new RTCIceCandidate(mid,index,string(c,"candidate")));
                }
                gotIce=true;break;
            }
            pause(500);
        }
        if(!gotIce)throw new IOException("No remote ICE candidates");
        for(int i=0;i<45;i++){check();if(peer.getConnectionState()==RTCPeerConnectionState.CONNECTED)return;pause(1000);}
        throw new IOException("RTC connection timed out");
    }
    private boolean open(String name){return channels.get(name).getState()==RTCDataChannelState.OPEN;}
    private void send(String name,Map<String,Object> payload)throws Exception{if(open(name))channels.get(name).send(new RTCDataChannelBuffer(ByteBuffer.wrap(JSON.toJson(payload).getBytes(StandardCharsets.UTF_8)),false));}
    private void binary(ByteBuffer packet)throws Exception{if(open("input")&&channels.get("input").getBufferedAmount()<8192)channels.get("input").send(new RTCDataChannelBuffer(packet,true));}
    private double now(){return (System.nanoTime()-epoch)/1e6;}
    static ByteBuffer header(int type,int sequence,double now,int body){return ByteBuffer.allocate(14+body).order(ByteOrder.LITTLE_ENDIAN).putShort((short)type).putInt(sequence).putDouble(now);}
    static short axis(JsonObject f,String key,int sign){return (short)(Math.max(-1,Math.min(1,number(f,key)*sign))*32767);}
    static short trigger(JsonObject f,String key){return (short)(Math.max(0,Math.min(1,number(f,key)))*65535);}
    static ByteBuffer padPacket(int seq,double now,Map<Integer,JsonObject> frames){
        ByteBuffer b=header(2,seq,now,1+23*frames.size());b.put((byte)frames.size());
        for(var e:frames.entrySet()){
            JsonObject f=e.getValue();int mask=0;for(int i=0;i<BUTTONS.length;i++)if(number(f,BUTTONS[i])>.5)mask|=1<<(i+1);
            b.put(e.getKey().byteValue()).putShort((short)mask)
             .putShort(axis(f,"LeftThumbXAxis",1)).putShort(axis(f,"LeftThumbYAxis",-1))
             .putShort(axis(f,"RightThumbXAxis",1)).putShort(axis(f,"RightThumbYAxis",-1))
             .putShort(trigger(f,"LeftTrigger")).putShort(trigger(f,"RightTrigger")).putInt(1).putInt(0x01000000);
        }
        return b.flip();
    }
    private void message(String target,Map<String,Object> content)throws Exception{
        send("message",obj("type","Message","id",UUID.randomUUID().toString(),"cv","","target","/streaming"+target,"content",JSON.toJson(content)));
    }
    private void configure()throws Exception{
        message("/systemUi/configuration",obj("version",List.of(0,2,0),"systemUis",List.of()));
        message("/properties/clientappinstallidchanged",obj("clientAppInstallId",UUID.randomUUID().toString()));
        message("/characteristics/orientationchanged",obj("orientation",0));
        message("/characteristics/touchinputenabledchanged",obj("touchInputEnabled",false));
        message("/characteristics/clientdevicecapabilities",obj("supportsCustomResolution",true,"supportsHevc",false,"supportsHdr",false,"supportsFps",FPS,"maxWidth",WIDTH,"maxHeight",HEIGHT,"maxBitrateKbps",8000,"video",obj("width",WIDTH,"height",HEIGHT,"maxWidth",WIDTH,"maxHeight",HEIGHT,"maxBitrateKbps",8000)));
        message("/characteristics/dimensionschanged",obj("horizontal",WIDTH,"vertical",HEIGHT,"preferredWidth",WIDTH,"preferredHeight",HEIGHT,"safeAreaLeft",0,"safeAreaTop",0,"safeAreaRight",WIDTH,"safeAreaBottom",HEIGHT,"supportsCustomResolution",true));
    }
    private void input()throws Exception{
        if(!open("input")||!open("control")||!handshake)return;
        Map<Integer,JsonObject> frames=new TreeMap<>();
        try{JsonElement p=JSON.toJsonTree(controllerFrames.get());
            if(p!=null&&p.isJsonArray())for(JsonElement e:p.getAsJsonArray()){JsonObject f=object(e);if(f.has("GamepadIndex")){int slot=(int)number(f,"GamepadIndex");if(slot>=0&&slot<4)frames.put(slot,f);}}
        }catch(RuntimeException ignored){}
        Set<Integer> next=new TreeSet<>(frames.keySet());
        for(int slot:next)if(!activePads.contains(slot))send("control",obj("message","gamepadChanged","gamepadIndex",slot,"wasAdded",true));
        for(int slot:activePads)if(!next.contains(slot))frames.put(slot,new JsonObject());
        if(!frames.isEmpty())binary(padPacket(sequence++,now(),frames));
        for(int slot:activePads)if(!next.contains(slot))send("control",obj("message","gamepadChanged","gamepadIndex",slot,"wasAdded",false));
        activePads.clear();activePads.addAll(next);
    }
    private void loop()throws Exception{
        label("Streaming (Java RTC)");
        long lastState=System.nanoTime(),lastKeep=lastState;int failures=0;boolean lastMute=muted;
        while(!closing.get()){
            if(lastMute!=muted){peer.setAudioPlayout(!muted);lastMute=muted;}
            if(open("message")&&!messageOpened){messageOpened=true;send("message",obj("type","Handshake","version","messageV1","id",UUID.randomUUID().toString(),"cv","0"));}
            if(open("control")&&!controlOpened){controlOpened=true;send("control",obj("message","authorizationRequest","accessKey","4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E"));Map<String,Object> size=obj("width",WIDTH,"height",HEIGHT);send("control",obj("message","VideoPreference","type","VideoPreference","resolution",size,"dimensions",size,"maxFPS",FPS));}
            if(open("input")&&!inputOpened){inputOpened=true;binary(header(8,sequence++,now(),1).put((byte)0).flip());}
            String raw;while((raw=messages.poll())!=null){
                JsonObject m;try{m=object(JsonParser.parseString(raw));}catch(JsonParseException ex){continue;}
                if(!handshake&&string(m,"type").equals("HandshakeAck")){handshake=true;configure();}
                if(string(m,"target").equals("/streaming/sessionLifetimeManagement/serverInitiatedDisconnect")){
                    send("message",obj("type","TransactionComplete","id",string(m,"id"),"content","","cv",""));closing.set(true);
                }
            }
            input();
            long n=System.nanoTime();
            if(n-lastState>=TimeUnit.SECONDS.toNanos(5)){
                lastState=n;send("control",obj("message","videoKeyframeRequested","ifrRequested",true));
                try{if(TERMINAL.contains(string(state(),"state")))closing.set(true);
                    if(n-lastKeep>=TimeUnit.SECONDS.toNanos(25)){request("keepalive",Map.of(),15);lastKeep=n;}
                    failures=0;
                }catch(IOException e){if(++failures>=3)throw e;}
            }
            Thread.sleep(33);
        }
    }
    private void run()throws Exception{
        try{check();ui();check();negotiate();loop();}
        finally{
            closing.set(true);
            if(video!=null&&sink!=null)try{video.removeSink(sink);}catch(Exception ignored){}
            for(var e:channels.entrySet())try{RTCDataChannel c=e.getValue();if(e.getKey().equals("message"))c.unregisterObserver();c.close();c.dispose();}catch(Exception ignored){}
            if(peer!=null)try{peer.close();}catch(Exception ignored){}
            if(factory!=null)try{factory.dispose();}catch(Exception ignored){}
            if(started)try{request("stop",Map.of(),5);}catch(Exception ignored){}
            if(window!=null)SwingUtilities.invokeAndWait(window::dispose);
        }
    }
    /** Starts an in-process worker; completion fires only after native and server cleanup. */
    public static Runnable open(int port,String secret,String title,boolean audio,
            java.util.function.Supplier<List<Map<String,Object>>> frames,CompletableFuture<Void> finished){
        NativeRtcPlayer session=new NativeRtcPlayer(port,secret,title,audio,frames);
        Thread worker=new Thread(()->{
            try{session.run();finished.complete(null);}
            catch(InterruptedException e){finished.complete(null);}
            catch(Throwable e){finished.completeExceptionally(new IllegalStateException("Java RTC failed: "+e.getClass().getSimpleName()));}
        },"wiiuu-java-rtc");
        worker.setDaemon(true);
        worker.start();
        // Do not interrupt a native JNI call or a pending session start. The
        // cancellation flag is checked between bounded signaling calls.
        return ()->session.closing.set(true);
    }
    public static void main(String[] args){
        if(args.length==1&&args[0].equals("--self-test")){
            JsonObject f=new JsonObject();f.addProperty("A",1);f.addProperty("LeftThumbYAxis",-1);f.addProperty("LeftTrigger",1);
            ByteBuffer b=padPacket(7,25,Map.of(2,f));
            if(b.remaining()!=38||b.getShort(0)!=2||b.getInt(2)!=7||b.get(15)!=2||b.getShort(16)!=16||b.getShort(20)!=32767||Short.toUnsignedInt(b.getShort(26))!=65535)throw new AssertionError("Input wire format");
            if(remoteIce(JsonParser.parseString("{\"iceCandidates\":[null,{}, {\"candidate\":\"a=candidate:1 1 udp 1 127.0.0.1 1234 typ host\",\"sdpMid\":\"0\"}]}")).size()!=1)throw new AssertionError("ICE filtering");
            System.out.println("Java RTC protocol fixtures passed");return;
        }
        throw new IllegalArgumentException("Use --self-test or the in-process open API");
    }
}
