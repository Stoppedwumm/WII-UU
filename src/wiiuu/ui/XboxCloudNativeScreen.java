package wiiuu.ui;

import wiiuu.core.Config;
import wiiuu.core.MiniJson;
import wiiuu.core.PluginStore;
import wiiuu.core.XboxCloudRuntime;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.geom.*;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Xbox Cloud Gaming inside the WII-UU TV/GamePad UI.
 *
 * Paint, controller navigation and search keyboard use the same theme palette
 * and render surface as NativePluginScreen. The Microsoft/Xbox sidecar stays
 * entirely separate. Only playing a stream opens the isolated Chromium app.
 */
final class XboxCloudNativeScreen implements AutoCloseable {
    private record Game(String id, String name) {}
    private record Hit(Rectangle2D area, Runnable action, boolean card) {}

    private final Config config;
    private final PluginStore.Entry entry;
    private final Runnable repaint, exit;
    private final List<Hit> hits = new ArrayList<>();
    private final List<Game> games = new ArrayList<>();
    private Backend backend;
    private volatile Process browser;
    private boolean closed, startingBackend, installing, signedIn, pairing, searchEditing;
    private String status = "Checking the Xbox Cloud Gaming runtime...";
    private String loginCode = "";
    private String verificationUrl = "https://www.microsoft.com/link";
    private String query = "";
    private int focus, page, columns = 3, rows = 2, pageSize = 6;

    XboxCloudNativeScreen(Config config, PluginStore.Entry entry, Runnable repaint, Runnable exit) {
        this.config = config;
        this.entry = entry;
        this.repaint = repaint;
        this.exit = exit;
        if (XboxCloudRuntime.ready(config)) connectBackend();
        else status = "Install the Xbox Cloud runtime to sign in and play.";
    }

    private static String shortened(Graphics2D g, String s, float width) {
        return UiText.ellipsize(s == null ? "" : s, g.getFontMetrics(), Math.max(0, width));
    }
    private static void label(Graphics2D g, String s, float x, float y, int style, float size, Color color, float limit) {
        g.setFont(MenuView.font(style, size));
        g.setColor(color);
        g.drawString(shortened(g, s, limit), x, y);
    }
    private static void center(Graphics2D g, String s, float x, float y) {
        g.drawString(s, x - g.getFontMetrics().stringWidth(s) * 0.5f, y);
    }
    private static void rounded(Graphics2D g, float x, float y, float w, float h, float r, Color color) {
        g.setColor(color);
        g.fill(new RoundRectangle2D.Float(x, y, w, h, r, r));
    }
    private void action(Graphics2D g, float x, float y, float w, float h,
                        String title, Runnable onClick, float u, boolean primary) {
        int index = hits.size();
        boolean selected = focus == index;
        Color bg = primary ? MenuView.ACCENT : MenuView.CARD;
        rounded(g, x, y, w, h, 16*u, bg);
        if (selected) {
            g.setColor(MenuView.ACCENT);
            g.setStroke(new BasicStroke(3*u));
            g.draw(new RoundRectangle2D.Float(x-3*u,y-3*u,w+6*u,h+6*u,18*u,18*u));
        }
        g.setFont(MenuView.font(Font.BOLD, 15*u));
        g.setColor(primary ? Color.WHITE : MenuView.TEXT);
        center(g, shortened(g,title,w-16*u), x+w/2f, y+h/2f+5*u);
        hits.add(new Hit(new Rectangle2D.Float(x, y, w, h), onClick, false));
    }
    private void card(Graphics2D g, float x, float y, float w, float h, Game game, float u, int index) {
        boolean selected = hits.size() == focus;
        rounded(g, x, y, w, h, 20*u, MenuView.CARD);
        rounded(g, x+12*u,y+12*u,w-24*u,Math.max(40*u,h*0.47f),14*u,
                selected ? MenuView.ACCENT : MenuView.dark ? new Color(47, 61, 80) : new Color(215,228,240));
        g.setColor(selected ? Color.WHITE : MenuView.TEXT);
        g.setFont(MenuView.font(Font.BOLD, 32*u));
        String initials = game.name().isBlank() ? "?" : game.name().trim().substring(0,1).toUpperCase(Locale.ROOT);
        center(g, initials, x+w/2f, y+Math.max(40*u,h*0.47f)*0.65f + 12*u);
        g.setFont(MenuView.font(Font.BOLD, 17*u)); g.setColor(MenuView.TEXT);
        center(g, shortened(g, game.name(),w-24*u), x+w/2f, y+Math.max(40*u,h*0.47f)+43*u);
        g.setFont(MenuView.font(Font.PLAIN, 12*u));g.setColor(MenuView.TEXT_DIM);
        center(g, "A  Play", x+w/2f, y+h-16*u);
        if (selected) {
            g.setStroke(new BasicStroke(3*u));g.setColor(MenuView.ACCENT);
            g.draw(new RoundRectangle2D.Float(x-3*u,y-3*u,w+6*u,h+6*u,22*u,22*u));
        }
        hits.add(new Hit(new Rectangle2D.Float(x, y, w, h), () -> startGame(game), true));
    }

    void paint(Graphics2D g, int width, int height) {
        float u = Math.max(0.54f,Math.min(width/1200f,height/760f));
        float pad = 32*u, titleY = 64*u, header = 130*u;
        g.setPaint(new GradientPaint(0,0,MenuView.bgTop(),0,height,MenuView.bgBottom()));
        g.fillRect(0,0,width,height);
        // A restrained version of the WII-UU menu's rounded shelf geometry.
        g.setColor(new Color(MenuView.ACCENT.getRed(),MenuView.ACCENT.getGreen(),
                MenuView.ACCENT.getBlue(),MenuView.dark?22:12));
        for(int n=0;n<6;n++)g.fill(new RoundRectangle2D.Float(width*0.52f+n*45*u,
                height*0.70f+n*24*u,300*u,160*u,90*u,90*u));
        hits.clear();
        label(g,"Xbox Cloud Gaming",pad,titleY,Font.BOLD,34*u,MenuView.TEXT,width-pad*2-180*u);
        label(g,"WII-UU  /  APPS",pad,96*u,Font.BOLD,13*u,MenuView.TEXT_DIM,width-pad*2);
        action(g,width-pad-117*u,26*u,117*u,48*u,"Close",exit,u,false);
        g.setColor(MenuView.dark ? new Color(100,113,134,120) : new Color(100,113,134,65));
        g.setStroke(new BasicStroke(1*u));
        g.drawLine((int)pad,(int)(112*u),(int)(width-pad),(int)(112*u));
        if (!XboxCloudRuntime.ready(config)) {
            paintInstall(g,width,height,u);
        } else if(!signedIn || pairing) {
            paintSignIn(g,width,height,u);
        } else {
            paintLibrary(g,width,height,u);
        }
        if(searchEditing) paintSearch(g,width,height,u);
        if(hits.isEmpty()) focus=0;
        else focus=Math.max(0,Math.min(focus,hits.size()-1));
        label(g,status,pad,height-40*u,Font.PLAIN,15*u,MenuView.TEXT_DIM,width-pad*2);
        label(g,searchEditing ? "D-pad: Keys   A: Type   B: Done" :
                "A: Select   B: Back   L/R: Page   HOME: Return",
                pad,height-15*u,Font.PLAIN,12*u,MenuView.TEXT_DIM,width-pad*2);
    }
    private void paintInstall(Graphics2D g,int w,int h,float u) {
        float cw=Math.min(w-64*u,780*u), x=(w-cw)/2f, y=155*u;
        rounded(g,x,y,cw,Math.max(200*u,h-y-95*u),24*u,MenuView.CARD);
        label(g,"One-time setup",x+24*u,y+50*u,Font.BOLD,26*u,MenuView.TEXT,cw-48*u);
        label(g,"Install an isolated Chromium WebRTC player and Xbox sign-in service.",
                x+24*u,y+86*u,Font.PLAIN,15*u,MenuView.TEXT_DIM,cw-48*u);
        label(g,"Linux ARM64, x86-64, macOS and Windows x64",
                x+24*u,y+113*u,Font.PLAIN,14*u,MenuView.TEXT_DIM,cw-48*u);
        action(g,x+24*u,y+142*u,cw-48*u,48*u,
                installing?"Installing runtime...":"Install / update Xbox Cloud Gaming",
                this::installRuntime,u,true);
    }
    private void paintSignIn(Graphics2D g,int w,int h,float u) {
        float cardW=Math.min(w-64*u,820*u),x=(w-cardW)/2f,y=155*u;
        float cardH=Math.max(230*u,h-y-93*u);
        rounded(g,x,y,cardW,cardH,24*u,MenuView.CARD);
        label(g,pairing?"Link your Microsoft account":"Sign in to Xbox Cloud Gaming",
                x+30*u,y+49*u,Font.BOLD,25*u,MenuView.TEXT,cardW-60*u);
        if(pairing) {
            label(g,"Enter this code on another device",x+30*u,y+85*u,Font.PLAIN,16*u,MenuView.TEXT_DIM,cardW-60*u);
            rounded(g,x+30*u,y+100*u,cardW-60*u,90*u,15*u,
                    MenuView.dark?new Color(47,61,80):new Color(222,232,244));
            g.setColor(MenuView.ACCENT);g.setFont(MenuView.font(Font.BOLD,Math.min(35*u,cardW/13)));
            center(g,loginCode,x+cardW/2f,y+156*u);
            label(g,"Visit "+verificationUrl,x+30*u,y+222*u,Font.BOLD,16*u,MenuView.TEXT,cardW-60*u);
            action(g,x+30*u,y+242*u,(cardW-70*u)/2f,46*u,"Copy code",this::copyCode,u,true);
            action(g,x+40*u+(cardW-70*u)/2f,y+242*u,(cardW-70*u)/2f,46*u,
                    "Refresh games",this::loadGames,u,false);
        } else {
            label(g,"Use microsoft.com/link on your phone or another computer.",
                    x+30*u,y+85*u,Font.PLAIN,16*u,MenuView.TEXT_DIM,cardW-60*u);
            label(g,"Your password is never entered into WII-UU.",
                    x+30*u,y+112*u,Font.PLAIN,15*u,MenuView.TEXT_DIM,cardW-60*u);
            action(g,x+30*u,y+145*u,cardW-60*u,53*u,
                    backend==null?"Connecting...":"Get login code",this::startLogin,u,true);
        }
    }
    private List<Game> filtered() {
        if(query.isBlank())return List.copyOf(games);
        String q=query.toLowerCase(Locale.ROOT).trim();
        return games.stream().filter(game->game.name().toLowerCase(Locale.ROOT).contains(q)).toList();
    }
    private void paintLibrary(Graphics2D g,int w,int h,float u) {
        float pad=32*u, top=140*u,gap=15*u;
        int ctl=hits.size();
        float bw=Math.min(230*u,(w-2*pad-30*u)/3);
        action(g,pad,top,bw,45*u,"Search: "+(query.isBlank()?"All games":query),this::openSearch,u,false);
        action(g,pad+bw+gap,top,bw,45*u,"Reload",this::loadGames,u,false);
        action(g,pad+2*(bw+gap),top,bw,45*u,"Sign out",this::logout,u,false);
        float gridY=208*u, footer=75*u;
        columns=w>=1230?4:w>=760?3:2;
        rows=h>=730?2:1;
        pageSize=columns*rows;
        List<Game> found=filtered();
        int pages=Math.max(1,(found.size()+pageSize-1)/pageSize);
        page=Math.max(0,Math.min(page,pages-1));
        float cardWidth=(w-2*pad-(columns-1)*gap)/columns;
        float cardHeight=Math.max(93*u,(h-gridY-footer-(rows-1)*gap)/rows);
        for(int local=0;local<pageSize;local++){
            int idx=page*pageSize+local;
            if(idx>=found.size())break;
            float x=pad+(local%columns)*(cardWidth+gap);
            float y=gridY+(local/columns)*(cardHeight+gap);
            card(g,x,y,cardWidth,cardHeight,found.get(idx),u,idx);
        }
        if(found.isEmpty()) {
            label(g,query.isBlank()?"No available games yet":"No titles match your search.",
                    pad,gridY+65*u,Font.BOLD,22*u,MenuView.TEXT,w-2*pad);
        }
        label(g,found.size()+" games    Page "+(page+1)+" / "+pages,
                w-pad-270*u,top+31*u,Font.PLAIN,13*u,MenuView.TEXT_DIM,270*u);
    }
    private void paintSearch(Graphics2D g,int w,int h,float u) {
        // One single UI surface; no JOptionPane or separate desktop window.
        g.setColor(new Color(0,0,0,MenuView.dark?190:145));
        g.fillRect(0,0,w,h);
        hits.clear();
        float cw=Math.min(w-48*u,720*u),ch=Math.min(h-40*u,575*u);
        float x=(w-cw)/2f,y=(h-ch)/2f;
        rounded(g,x,y,cw,ch,24*u,MenuView.CARD);
        label(g,"Find a game",x+24*u,y+42*u,Font.BOLD,25*u,MenuView.TEXT,cw-48*u);
        rounded(g,x+24*u,y+56*u,cw-48*u,52*u,12*u,
                MenuView.dark?new Color(40,48,61):new Color(219,230,240));
        label(g,query.isEmpty()?"Type a game name...":query,
                x+38*u,y+91*u,Font.PLAIN,20*u,MenuView.TEXT,cw-76*u);
        String keys="ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        int kc=6,kr=6;
        float gap=7*u, keyW=(cw-48*u-(kc-1)*gap)/kc;
        float available=ch-208*u;
        float keyH=Math.max(20*u,Math.min(48*u,(available-(kr-1)*gap)/kr));
        float keysY=y+126*u;
        for(int n=0;n<keys.length();n++){
            final char letter=keys.charAt(n);
            float bx=x+24*u+(n%kc)*(keyW+gap), by=keysY+(n/kc)*(keyH+gap);
            action(g,bx,by,keyW,keyH,String.valueOf(letter),()->typeLetter(letter),u,false);
        }
        float bottom=keysY+kr*(keyH+gap)+9*u, half=(cw-58*u)/3f;
        action(g,x+24*u,bottom,half,43*u,"Delete",this::erase,u,false);
        action(g,x+29*u+half,bottom,half,43*u,"Clear",()->{query="";page=0;repaint.run();},u,false);
        action(g,x+34*u+2*half,bottom,half,43*u,"Done",this::finishSearch,u,true);
    }
    private void typeLetter(char c) {
        if(query.length()<72){query+=c;page=0;repaint.run();}
    }
    private void erase() {
        if(!query.isEmpty()){query=query.substring(0,query.length()-1);page=0;repaint.run();}
    }
    private void openSearch() {
        searchEditing=true;focus=0;repaint.run();
    }
    private void finishSearch() {
        searchEditing=false;focus=1;page=0;repaint.run();
    }
    boolean wantsKeys() { return searchEditing; }
    boolean keyPressed(KeyEvent e) {
        if(!searchEditing)return false;
        switch(e.getKeyCode()){
            case KeyEvent.VK_ESCAPE,KeyEvent.VK_ENTER -> finishSearch();
            case KeyEvent.VK_BACK_SPACE -> erase();
            case KeyEvent.VK_LEFT -> navigate(-1,0);
            case KeyEvent.VK_RIGHT -> navigate(1,0);
            case KeyEvent.VK_UP -> navigate(0,-1);
            case KeyEvent.VK_DOWN -> navigate(0,1);
            default -> {return false;}
        }
        return true;
    }
    boolean keyTyped(char c) {
        if(!searchEditing)return false;
        if(c>=32 && !Character.isISOControl(c))typeLetter(c);
        return true;
    }
    void navigate(int dx,int dy) {
        if(hits.isEmpty())return;
        focus=Math.max(0,Math.min(focus,hits.size()-1));
        Rectangle2D current=hits.get(focus).area();
        double cx=current.getCenterX(),cy=current.getCenterY();
        double best=Double.MAX_VALUE;int chosen=-1;
        for(int n=0;n<hits.size();n++){
            if(n==focus)continue;
            Rectangle2D next=hits.get(n).area();
            double xx=next.getCenterX()-cx, yy=next.getCenterY()-cy;
            if(dx!=0 && xx*dx<=1)continue;
            if(dy!=0 && yy*dy<=1)continue;
            double primary=dx!=0?Math.abs(xx):Math.abs(yy);
            double secondary=dx!=0?Math.abs(yy):Math.abs(xx);
            double score=primary+secondary*2.2;
            if(score<best){best=score;chosen=n;}
        }
        if(chosen<0) {
            if(dy!=0&&!searchEditing&&signedIn&&dy<0&&focus>3)page(-1);
            else if(dy!=0&&!searchEditing&&signedIn&&dy>0)page(1);
            else Sfx.bump();
            return;
        }
        focus=chosen;Sfx.move();repaint.run();
    }
    void activate() {
        if(hits.isEmpty()||focus<0||focus>=hits.size())return;
        Sfx.select();
        hits.get(focus).action().run();
    }
    void page(int delta) {
        if(!signedIn||searchEditing)return;
        int pages=Math.max(1,(filtered().size()+Math.max(1,pageSize)-1)/Math.max(1,pageSize));
        int next=Math.max(0,Math.min(pages-1,page+Integer.signum(delta)));
        if(next==page){Sfx.bump();return;}
        page=next;focus=4;Sfx.move();repaint.run();
    }
    void back() {
        if(searchEditing){finishSearch();return;}
        Sfx.back();exit.run();
    }
    void click(int x,int y) {
        for(int n=0;n<hits.size();n++)
            if(hits.get(n).area().contains(x,y)){focus=n;activate();return;}
    }

    private void installRuntime() {
        if(installing||XboxCloudRuntime.installing())return;
        installing=true;status="Installing Xbox Cloud Gaming components...";repaint.run();
        XboxCloudRuntime.install(config,message->SwingUtilities.invokeLater(()->{
            if(closed)return;
            status=message;
            if(!XboxCloudRuntime.installing()) {
                installing=false;
                if(XboxCloudRuntime.ready(config))connectBackend();
            }
            repaint.run();
        }));
    }
    private void connectBackend() {
        if(backend!=null||startingBackend||closed||!XboxCloudRuntime.ready(config))return;
        startingBackend=true;status="Starting local Xbox service...";repaint.run();
        CompletableFuture.runAsync(()->{
            try {
                Path root=XboxCloudRuntime.home(config);
                Path user=Files.createDirectories(config.userHome().resolve("xcloud"));
                Backend next=new Backend(root,user,message->SwingUtilities.invokeLater(()->onMessage(message)));
                SwingUtilities.invokeLater(()->{
                    startingBackend=false;
                    if(closed){next.close();return;}
                    backend=next;status="Ready. Sign in with Microsoft to continue.";
                    command("signedIn",result->{
                        signedIn=Boolean.TRUE.equals(result.get("signedIn"));
                        if(signedIn)loadGames();
                        repaint.run();
                    });
                    repaint.run();
                });
            } catch(Exception error) {
                SwingUtilities.invokeLater(()->{
                    startingBackend=false;
                    if(!closed){status="Xbox service failed to start: "+error.getMessage();repaint.run();}
                });
            }
        });
    }
    private void onMessage(Map<String,Object> message) {
        if(closed)return;
        String type=MiniJson.string(message.get("event"));
        if("code".equals(type)){
            String code=MiniJson.string(message.get("code"));
            String url=MiniJson.string(message.get("url"));
            if(code==null||code.isBlank()){status="Microsoft returned an invalid login code.";return;}
            loginCode=code;
            if(url!=null&&(url.equals("https://www.microsoft.com/link")||url.equals("https://microsoft.com/link")))
                verificationUrl=url;
            pairing=true;status="Waiting for your approval at "+verificationUrl;
            focus=1;
        }else if("signedIn".equals(type)){
            signedIn=true;pairing=false;loginCode="";focus=0;
            status="Signed in. Loading your games...";
            loadGames();
        }else if("authError".equals(type)){
            pairing=false;status="Microsoft sign-in failed: "+MiniJson.string(message.get("message"));
        }else if("ended".equals(type)){
            backend=null;status="The local Xbox service stopped.";
        }
        repaint.run();
    }
    private void command(String cmd,Consumer<Map<String,Object>> done) {
        if(backend==null){status="Xbox service is not running.";repaint.run();return;}
        backend.call(cmd).whenComplete((result,error)->SwingUtilities.invokeLater(()->{
            if(closed)return;
            if(error!=null){status=cmd+": "+error.getMessage();repaint.run();}
            else{done.accept(result);repaint.run();}
        }));
    }
    private void startLogin() {
        if(backend==null){connectBackend();return;}
        status="Requesting a Microsoft pairing code...";
        command("auth",result->{
            if(!pairing)status="Waiting for Microsoft's device-code response...";
        });
    }
    private void copyCode() {
        if(loginCode.isBlank())return;
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(loginCode),null);
            status="Code copied to clipboard.";
        }catch(IllegalStateException | HeadlessException error){status="Could not copy code; enter it manually.";}
        repaint.run();
    }
    private void loadGames() {
        if(backend==null){connectBackend();return;}
        status="Loading available Xbox Cloud Gaming titles...";
        command("games",result->{
            games.clear();
            for(Object item:MiniJson.array(result.get("games"))){
                Map<String,Object> row=MiniJson.object(item);
                String id=MiniJson.string(row.get("id")),name=MiniJson.string(row.get("name"));
                if(id!=null&&!id.isBlank()&&name!=null&&!name.isBlank())
                    games.add(new Game(id,name));
            }
            games.sort(Comparator.comparing(Game::name,String.CASE_INSENSITIVE_ORDER));
            page=0;focus=4;
            status=games.size()+" playable games available.";
        });
    }
    private void logout() {
        if(browser!=null&&browser.isAlive())stopGame();
        command("logout",result->{
            signedIn=false;pairing=false;loginCode="";games.clear();page=0;focus=0;query="";
            status="Signed out. Get a new Microsoft login code to continue.";
        });
    }
    private void startGame(Game chosen) {
        if(closed||backend==null||!signedIn)return;
        Process old=browser;
        if(old!=null&&old.isAlive()){status="A cloud stream is already running.";repaint.run();return;}
        try {
            String url="http://127.0.0.1:"+backend.port+
                    "/?title="+URLEncoder.encode(chosen.id(),StandardCharsets.UTF_8)+"#"+backend.secret;
            Path profile=Files.createDirectories(config.userHome().resolve("xcloud/chrome-profile"));
            ProcessBuilder builder=new ProcessBuilder(
                    XboxCloudRuntime.chromium(config).toString(),
                    "--user-data-dir="+profile.toAbsolutePath(),
                    "--app="+url,"--no-first-run","--disable-sync","--disable-background-networking");
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            Process process=builder.start();
            browser=process;
            PluginStore.trackExternal(config,entry,process);
            status="Streaming "+chosen.name()+" in Chromium. Return here to select another game.";
            process.onExit().thenRun(()->SwingUtilities.invokeLater(()->{
                if(browser==process){browser=null;if(!closed) {
                    status="Stream closed. Choose another game.";
                    command("stop",result->repaint.run());
                    repaint.run();
                }}
            }));
        }catch(Exception error){status="Could not start Chromium: "+error.getMessage();}
        repaint.run();
    }
    private void stopGame() {
        Process p=browser;
        if(p!=null&&p.isAlive()){p.descendants().forEach(ProcessHandle::destroy);p.destroy();}
        browser=null;
        command("stop",result->{status="Xbox stream stopped.";});
    }
    @Override public void close() {
        if(closed)return;
        closed=true;
        Process p=browser;
        if(p!=null&&p.isAlive()){p.descendants().forEach(ProcessHandle::destroy);p.destroy();}
        browser=null;
        if(backend!=null)backend.close();
        hits.clear();games.clear();
    }

    private static final class Backend implements AutoCloseable {
        private final Process process;
        private final BufferedWriter writer;
        private final AtomicInteger serial = new AtomicInteger();
        private final ConcurrentHashMap<Integer, CompletableFuture<Map<String, Object>>> requests =
                new ConcurrentHashMap<>();
        private final Consumer<Map<String, Object>> onEvent;
        private volatile int port;
        private volatile String secret;
        private final CompletableFuture<Boolean> initialized = new CompletableFuture<>();

        Backend(Path runtime, Path user, Consumer<Map<String, Object>> onEvent)
                throws IOException, InterruptedException {
            this.onEvent = onEvent;
            ProcessBuilder builder = new ProcessBuilder("node", runtime.resolve("service.cjs").toString(),
                    runtime.toString(), user.toString()).directory(runtime.toFile())
                    .redirectError(ProcessBuilder.Redirect.appendTo(runtime.resolve("backend.log").toFile()));
            process = builder.start();
            writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(this::read, "wiiuu-xcloud-backend");
            reader.setDaemon(true); reader.start();
            try { initialized.get(15, TimeUnit.SECONDS); }
            catch (Exception error) {
                process.destroyForcibly();
                throw new IOException("Xbox backend did not become ready (see backend.log)", error);
            }
        }

        private void read() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Map<String, Object> msg;
                    try { msg = MiniJson.object(MiniJson.parse(line)); }
                    catch (RuntimeException ignored) { continue; }
                    if ("ready".equals(msg.get("event"))) {
                        Double value = MiniJson.number(msg.get("port"));
                        port = value == null ? 0 : value.intValue();
                        secret = MiniJson.string(msg.get("secret"));
                        if (port > 0 && secret != null && secret.matches("[a-f0-9]{64}"))
                            initialized.complete(true);
                        else initialized.completeExceptionally(new IOException("Invalid backend handshake"));
                        continue;
                    }
                    Double id = MiniJson.number(msg.get("id"));
                    if (id != null) {
                        CompletableFuture<Map<String, Object>> promise = requests.remove(id.intValue());
                        if (promise != null) {
                            if (Boolean.TRUE.equals(msg.get("ok")))
                                promise.complete(MiniJson.object(msg.get("result")));
                            else promise.completeExceptionally(new IOException(
                                    String.valueOf(msg.getOrDefault("error", "Unknown Xbox error"))));
                        }
                    } else { onEvent.accept(msg); }
                }
            } catch (IOException ignored) { }
            finally {
                initialized.completeExceptionally(new IOException("Backend ended unexpectedly"));
                for (var item : requests.values())
                    item.completeExceptionally(new IOException("Xbox backend exited"));
                requests.clear();
                onEvent.accept(Map.of("event", "ended"));
            }
        }

        synchronized CompletableFuture<Map<String, Object>> call(String command) {
            int id = serial.incrementAndGet();
            CompletableFuture<Map<String, Object>> response = new CompletableFuture<>();
            requests.put(id, response);
            try {
                if (!List.of("auth", "games", "signedIn", "logout", "stop").contains(command))
                    throw new IOException("Unsupported native UI command");
                writer.write("{\"id\":" + id + ",\"cmd\":\"" + command + "\"}\n");
                writer.flush();
            } catch (IOException error) {
                requests.remove(id);
                response.completeExceptionally(error);
            }
            return response.orTimeout(75, TimeUnit.SECONDS);
        }

        @Override public void close() {
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
