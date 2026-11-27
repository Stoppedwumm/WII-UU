package wiiuu.xcloud;

import com.google.gson.*;
import dev.onvoid.webrtc.*;
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
    record Profile(int width,int height,int fps,int bitrate) {
        static Profile choose(String os,String arch,String override) {
            return switch(override.toLowerCase(Locale.ROOT)) {
                case "360p" -> new Profile(640,360,30,2000);
                case "540p" -> new Profile(960,540,30,3500);
                case "720p" -> new Profile(1280,720,30,8000);
                default -> new Profile(1280,720,30,8000);
            };
        }
    }
    private final Profile profile=Profile.choose(System.getProperty("os.name",""),System.getProperty("os.arch",""),
            System.getProperty("wiiuu.xcloud.profile","auto"));
    private final ExecutorService maintenance=Executors.newSingleThreadExecutor(r->{
        Thread t=new Thread(r,"xcloud-keepalive");t.setDaemon(true);return t;
    });
    private LatestFrame<dev.onvoid.webrtc.media.video.VideoFrame> frames;
    private volatile long lastVideo;
    private final java.util.concurrent.atomic.AtomicLong rendered=new java.util.concurrent.atomic.AtomicLong();
    private volatile String streamSize="";
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
    private FastVideoView view;
    private LoadingStage stage;
    private final java.util.function.Supplier<JComponent> loadingFactory;
    private final AtomicBoolean firstPicture=new AtomicBoolean();
    private final AtomicBoolean statsPending=new AtomicBoolean();
    private volatile String decoderStats="";
    private String statsId="";
    private double priorDecoded,priorDecodeTime,priorEmitted,priorJitterTime;
    private long priorStatsTime;
    private PeerConnectionFactory factory;
    private RTCPeerConnection peer;
    private volatile boolean muted, rtcFailed;
    private boolean started, handshake, inputOpened, controlOpened, messageOpened;
    private int sequence;
    private volatile VideoTrack video;
    private VideoTrackSink sink;
    private final java.util.function.Supplier<List<Map<String,Object>>> controllerFrames;
    private NativeRtcPlayer(int port,String secret,String title,boolean audio,java.util.function.Supplier<List<Map<String,Object>>> controllerFrames,
            java.util.function.Supplier<JComponent> loadingFactory) {
        this.secret=secret;
        this.title=title;
        this.controllerFrames=Objects.requireNonNull(controllerFrames);
        this.loadingFactory=Objects.requireNonNull(loadingFactory);
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
    private void label(String text){
        SwingUtilities.invokeLater(()->{
            if(window!=null)window.setTitle("WII-UU Xbox Cloud Player | "+text+(muted?" | Muted":""));
        });
    }
    private void loadingStatus(String text){
        label(text);
        SwingUtilities.invokeLater(()->{if(stage!=null)stage.status(text);});
    }
    private void ui()throws Exception{
        SwingUtilities.invokeAndWait(()->{
            window=new JFrame("WII-UU Xbox Cloud Player");
            window.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            window.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){closing.set(true);}});
            boolean armLinux=System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("linux")
                    &&Set.of("aarch64","arm64","arm","armv7l").contains(System.getProperty("os.arch","").toLowerCase(Locale.ROOT));
            view=new FastVideoView(armLinux);
            view.setPreferredSize(new Dimension(profile.width(),profile.height()));
            stage=new LoadingStage(loadingFactory.get(),view);
            stage.setPreferredSize(new Dimension(profile.width(),profile.height()));
            window.setContentPane(stage);
            // Keep the entire client area for video; controls do not consume any screen space.
            window.getRootPane().registerKeyboardAction(e->closing.set(true),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
            window.getRootPane().registerKeyboardAction(e->{
                muted=!muted;
                label(muted?"Audio muted":"Audio enabled");
            },KeyStroke.getKeyStroke(KeyEvent.VK_M,0),JComponent.WHEN_IN_FOCUSED_WINDOW);
            window.setTitle("WII-UU Xbox Cloud Player | Preparing Java RTC..."+(muted?" | Muted":""));
            window.pack();window.setLocationRelativeTo(null);window.setVisible(true);
        });
    }
    private void setup(){
        // Default WebRTC uses software H264 on Linux. Use hardware where available,
        // with the library's built-in software fallback. Pi 5 itself has no H264 accelerator.
        try {
            var decoders=new dev.onvoid.webrtc.media.video.codec.HardwareVideoDecoderFactory();
            System.out.println("[xcloud] Hardware decoder candidates: "+decoders.getHardwareCodecs());
            factory=PeerConnectionFactory.builder().setVideoDecoderFactory(decoders).build();
        } catch(RuntimeException | LinkageError unsupported) {
            factory=new PeerConnectionFactory();
        }
        frames=new LatestFrame<>("xcloud-video-render",frame->{
            if(closing.get())return;
            var buffer=frame.buffer;
            streamSize=buffer.getWidth()+"x"+buffer.getHeight();
            // If the server ignores the resolution hint, scale I420 before RGB conversion.
            double scale=Math.min(1,Math.min((double)profile.width()/buffer.getWidth(),(double)profile.height()/buffer.getHeight()));
            if(scale<1) {
                int w=Math.max(2,((int)(buffer.getWidth()*scale))&~1),h=Math.max(2,((int)(buffer.getHeight()*scale))&~1);
                var scaled=buffer.cropAndScale(0,0,buffer.getWidth(),buffer.getHeight(),w,h);
                try { view.accept(new dev.onvoid.webrtc.media.video.VideoFrame(scaled,frame.rotation,frame.timestampNs)); }
                finally { scaled.release(); }
            } else view.accept(frame);
            rendered.incrementAndGet();
            if(firstPicture.compareAndSet(false,true))
                SwingUtilities.invokeLater(()->{if(!closing.get())stage.beginIntro();});
        },dev.onvoid.webrtc.media.video.VideoFrame::release);
        System.out.println("[xcloud] Native profile "+profile.width()+"x"+profile.height()+" @ "+profile.fps()+" fps, "+profile.bitrate()+" kbps");
        peer=factory.createPeerConnection(new RTCConfiguration(),new PeerConnectionObserver(){
            public void onIceCandidate(RTCIceCandidate c){
                if(c!=null&&c.sdp!=null&&!c.sdp.isBlank())candidates.add(obj("candidate",c.sdp,"sdpMid",c.sdpMid,"sdpMLineIndex",c.sdpMLineIndex));
            }
            public void onConnectionChange(RTCPeerConnectionState s){
                if(s==RTCPeerConnectionState.FAILED){rtcFailed=true;closing.set(true);}
                else if(s==RTCPeerConnectionState.CLOSED)closing.set(true);
            }
            public void onTrack(RTCRtpTransceiver t){
                if(t.getReceiver().getTrack() instanceof VideoTrack v && video==null){
                    video=v;
                    // SHARED delivers an owned reference. Transfer it into the bounded
                    // mailbox; it releases both replaced and consumed frames.
                    sink=frame->{lastVideo=System.nanoTime();frames.offer(frame);};
                    v.addSink(sink,VideoFrameAccess.SHARED);
                }
            }
        });
        if(peer==null)throw new IllegalStateException("Peer creation failed");
        peer.setAudioRecording(false);
        // Do not play game audio underneath the loading and logo screens.
        peer.setAudioPlayout(false);
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
        loadingStatus("Requesting Xbox session...");
        request("start",obj("titleId",title,"videoWidth",profile.width(),"videoHeight",profile.height()),90);started=true;
        loadingStatus("Waiting for Xbox server...");
        boolean transferred=false,ready=false;
        for(int i=0;i<240;i++){check();String s=string(state(),"state");
            if(s.equals("Provisioned")){ready=true;break;}
            if(TERMINAL.contains(s))throw new IOException("Session ended");
            if(s.equals("ReadyToConnect")&&!transferred){loadingStatus("Connecting to Xbox server...");request("connect",Map.of(),35);transferred=true;}
            pause(1000);
        }
        if(!ready)throw new IOException("Provisioning timed out");
        loadingStatus("Negotiating Java WebRTC...");
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
        message("/characteristics/clientdevicecapabilities",obj("supportsCustomResolution",true,"supportsHevc",false,"supportsHdr",false,"supportsFps",profile.fps(),"maxWidth",profile.width(),"maxHeight",profile.height(),"maxBitrateKbps",profile.bitrate(),"video",obj("width",profile.width(),"height",profile.height(),"maxWidth",profile.width(),"maxHeight",profile.height(),"maxBitrateKbps",profile.bitrate())));
        message("/characteristics/dimensionschanged",obj("horizontal",profile.width(),"vertical",profile.height(),"preferredWidth",profile.width(),"preferredHeight",profile.height(),"safeAreaLeft",0,"safeAreaTop",0,"safeAreaRight",profile.width(),"safeAreaBottom",profile.height(),"supportsCustomResolution",true));
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
    private static double stat(Map<String,Object> a,String key){
        return a.get(key) instanceof Number n ? n.doubleValue() : Double.NaN;
    }
    private void sampleDecoder(){
        if(!statsPending.compareAndSet(false,true))return;
        try{
            peer.getStats(report->{
                try{
                    if(closing.get())return;
                    for(RTCStats entry:report.getStats().values()){
                        Map<String,Object> a=entry.getAttributes();
                        if(entry.getType()!=RTCStatsType.INBOUND_RTP||!"video".equals(a.get("kind"))&&!"video".equals(a.get("mediaType")))continue;
                        double decoded=stat(a,"framesDecoded"),decode=stat(a,"totalDecodeTime"),
                                emitted=stat(a,"jitterBufferEmittedCount"),jitter=stat(a,"jitterBufferDelay");
                        long timestamp=entry.getTimestamp();
                        if(entry.getId().equals(statsId)&&timestamp>priorStatsTime&&decoded>priorDecoded){
                            double df=decoded-priorDecoded,seconds=(timestamp-priorStatsTime)/1e6;
                            double decodeMs=(decode-priorDecodeTime)*1000/df;
                            double jitterMs=emitted>priorEmitted?(jitter-priorJitterTime)*1000/(emitted-priorEmitted):Double.NaN;
                            decoderStats=String.format(Locale.ROOT,"decode %.1f fps / %s ms | jitter %s ms",df/seconds,metric(decodeMs),metric(jitterMs));
                            System.out.println("[xcloud] "+decoderStats+" | decoder="+a.getOrDefault("decoderImplementation","unknown")
                                    +" | power efficient="+a.getOrDefault("powerEfficientDecoder","unknown")
                                    +" | packet loss="+a.getOrDefault("packetsLost","unknown")+" | decoder drops="+a.getOrDefault("framesDropped","unknown"));
                        }
                        statsId=entry.getId();priorStatsTime=timestamp;priorDecoded=decoded;priorDecodeTime=decode;priorEmitted=emitted;priorJitterTime=jitter;
                        break;
                    }
                }finally{statsPending.set(false);}
            });
        }catch(RuntimeException e){statsPending.set(false);}
    }
    private static String metric(double value){return Double.isFinite(value)&&value>=0?String.format(Locale.ROOT,"%.1f",value):"n/a";}
    private void loop()throws Exception{
        if(!firstPicture.get())loadingStatus("Waiting for the first video frame...");
        else label("Streaming (Java RTC)");
        long lastState=System.nanoTime(),lastKeep=lastState,lastReport=lastState,lastRendered=0,lastDropped=0,lastPainted=0;
        int failures=0;boolean lastPlayout=false;
        Future<Boolean> pending=null;
        while(!closing.get()){
            boolean playout=stage.videoVisible()&&!muted;
            if(lastPlayout!=playout){peer.setAudioPlayout(playout);lastPlayout=playout;}
            if(open("message")&&!messageOpened){messageOpened=true;send("message",obj("type","Handshake","version","messageV1","id",UUID.randomUUID().toString(),"cv","0"));}
            if(open("control")&&!controlOpened){controlOpened=true;send("control",obj("message","authorizationRequest","accessKey","4BDB3609-C1F1-4195-9B37-FEFF45DA8B8E"));Map<String,Object> size=obj("width",profile.width(),"height",profile.height());send("control",obj("message","VideoPreference","type","VideoPreference","resolution",size,"dimensions",size,"maxFPS",profile.fps()));}
            if(open("input")&&!inputOpened){inputOpened=true;binary(header(8,sequence++,now(),1).put((byte)0).flip());}
            String raw;while((raw=messages.poll())!=null){
                JsonObject m;try{m=object(JsonParser.parseString(raw));}catch(JsonParseException ex){continue;}
                if(!handshake&&string(m,"type").equals("HandshakeAck")){handshake=true;configure();}
                if(string(m,"target").equals("/streaming/sessionLifetimeManagement/serverInitiatedDisconnect")){
                    send("message",obj("type","TransactionComplete","id",string(m,"id"),"content","","cv",""));closing.set(true);
                }
            }
            if(stage.videoVisible())input();
            long n=System.nanoTime();
            // HTTP must never block the controller or message loop.
            if(pending!=null&&pending.isDone()){
                try{if(pending.get())closing.set(true);failures=0;}
                catch(ExecutionException e){if(++failures>=3)throw new IOException("Xbox session check failed",e.getCause());}
                pending=null;
            }
            if(pending==null&&n-lastState>=TimeUnit.SECONDS.toNanos(5)){
                lastState=n;
                // Periodic IDR bursts waste bandwidth/CPU; request only when video stalls.
                if(lastVideo==0||n-lastVideo>=TimeUnit.SECONDS.toNanos(2))
                    send("control",obj("message","videoKeyframeRequested","ifrRequested",true));
                boolean keep=n-lastKeep>=TimeUnit.SECONDS.toNanos(25);
                if(keep)lastKeep=n;
                pending=maintenance.submit(()->{
                    if(TERMINAL.contains(string(state(),"state")))return true;
                    if(keep)request("keepalive",Map.of(),15);
                    return false;
                });
            }
            if(n-lastReport>=TimeUnit.SECONDS.toNanos(5)){
                sampleDecoder();
                long total=rendered.get(),drops=frames.dropped(),painted=view.painted();
                double fps=(total-lastRendered)*1e9/(n-lastReport),paintFps=(painted-lastPainted)*1e9/(n-lastReport);
                String performance=String.format(Locale.ROOT,"%s | convert %.1f / paint %.1f fps | %d drops",
                        streamSize.isEmpty()?"waiting for video":streamSize,fps,paintFps,drops-lastDropped);
                label(performance+(decoderStats.isEmpty()?"":" | "+decoderStats));
                System.out.println("[xcloud] "+performance);
                lastReport=n;lastRendered=total;lastDropped=drops;lastPainted=painted;
            }
            Thread.sleep(16);
        }
    }
    private void run()throws Exception{
        try{check();ui();check();negotiate();loop();}
        finally{
            closing.set(true);
            maintenance.shutdownNow();
            if(video!=null&&sink!=null)try{video.removeSink(sink);}catch(Exception ignored){}
            if(frames!=null)frames.close();
            for(var e:channels.entrySet())try{RTCDataChannel c=e.getValue();if(e.getKey().equals("message"))c.unregisterObserver();c.close();c.dispose();}catch(Exception ignored){}
            if(peer!=null)try{peer.close();}catch(Exception ignored){}
            if(factory!=null)try{factory.dispose();}catch(Exception ignored){}
            if(window!=null)SwingUtilities.invokeAndWait(window::dispose);
            if(started)try{request("stop",Map.of(),5);}catch(Exception ignored){}
        }
    }
    /** Starts an in-process worker; completion fires only after native and server cleanup. */
    public static Runnable open(int port,String secret,String title,boolean audio,
            java.util.function.Supplier<List<Map<String,Object>>> frames,CompletableFuture<Void> finished,
            java.util.function.Supplier<JComponent> loadingFactory){
        NativeRtcPlayer session=new NativeRtcPlayer(port,secret,title,audio,frames,loadingFactory);
        Thread worker=new Thread(()->{
            try{session.run();if(session.rtcFailed)finished.completeExceptionally(new IllegalStateException("Java RTC connection failed"));else finished.complete(null);}
            catch(InterruptedException e){if(session.rtcFailed)finished.completeExceptionally(new IllegalStateException("Java RTC connection failed"));else finished.complete(null);}
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
            if(!Profile.choose("Linux","aarch64","auto").equals(new Profile(1280,720,30,8000))
                    ||Profile.choose("Windows","amd64","auto").width()!=1280
                    ||Profile.choose("Linux","arm64","720p").width()!=1280)throw new AssertionError("RTC quality profile");
            LatestFrame.selfTest();
            FastVideoView.selfTest();
            LoadingStage.selfTest();
            CloudLogoIntro.selfTest();
            System.out.println("Java RTC protocol and performance fixtures passed");return;
        }
        throw new IllegalArgumentException("Use --self-test or the in-process open API");
    }
}
