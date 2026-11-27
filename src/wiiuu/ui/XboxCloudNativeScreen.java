package wiiuu.ui;

import wiiuu.core.Config;
import wiiuu.core.MiniJson;
import wiiuu.core.PluginStore;
import wiiuu.core.XboxCloudRuntime;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.net.HttpURLConnection;
import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
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
    private record Game(String id, String name, String cover) {}
    private record Hit(Rectangle2D area, Runnable action, boolean card) {}

    private final Config config;
    private final PluginStore.Entry entry;
    private final Runnable repaint, exit;
    private final java.util.function.Supplier<List<Map<String, Object>>> controllerFrames;
    private final ScheduledExecutorService controllerSender = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "xcloud-controller-forwarder");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> controllerTask;
    private final List<Hit> hits = new ArrayList<>();
    private final List<Game> games = new ArrayList<>();
    private final Map<String,BufferedImage> coverImages = new LinkedHashMap<>();
    private final XboxCloudFavorites favorites;
    private final Map<Integer,Game> favoriteTargets = new HashMap<>();
    private int quickLaunchStart = -1, quickLaunchEnd = -1;
    private final Set<String> requestedCovers = new HashSet<>();
    private final ExecutorService coverLoader = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "xcloud-cover-image");
        thread.setDaemon(true);
        return thread;
    });
    private Backend backend;
    private Game pendingFavorite;
    void launchFavorite(XboxCloudFavorites.Favorite favorite) {
        pendingFavorite = new Game(favorite.id(), favorite.name(), favorite.cover());
        if (signedIn && backend != null) launchPendingFavorite();
    }
    private boolean launchPendingFavorite() {
        if (pendingFavorite == null) return false;
        Game game = pendingFavorite;
        pendingFavorite = null;
        startGame(game);
        return true;
    }
    private volatile Process browser;
    private NativeRtcSession nativeSession;
    private boolean playing() { return nativeSession!=null || (browser!=null&&browser.isAlive()); }
    private boolean streamActive;
    private Runnable streamActivity = () -> {};
    boolean streamActive() { return streamActive; }
    void onStreamActivity(Runnable listener) { streamActivity = listener; }
    private void setStreamActive(boolean active) {
        if (streamActive == active) return;
        streamActive = active;
        streamActivity.run();
    }
    private boolean closed, startingBackend, installing, signedIn, pairing, searchEditing;
    // Query only the local helper to recover if an asynchronous auth notification is delayed.
    private final javax.swing.Timer pairingStatusTimer = new javax.swing.Timer(3000, event -> checkLogin(false));
    private String status = "Checking the Xbox Cloud Gaming runtime...";
    private String loginCode = "";
    private String verificationUrl = "https://www.microsoft.com/link";
    private String query = "";
    private int focus, searchFocus, page, columns = 3, rows = 2, pageSize = 6;
    private boolean paintingSearch;

    /** Search uses its own focus; never highlight catalog buttons behind the modal. */
    private int selectedFocus() { return paintingSearch ? searchFocus : searchEditing ? -1 : focus; }

    XboxCloudNativeScreen(Config config, PluginStore.Entry entry, Runnable repaint, Runnable exit) {
        this(config, entry, repaint, exit, List::of);
    }

    XboxCloudNativeScreen(Config config, PluginStore.Entry entry, Runnable repaint, Runnable exit,
                         java.util.function.Supplier<List<Map<String, Object>>> controllerFrames) {
        this.controllerFrames = controllerFrames;
        this.config = config;
        this.favorites = new XboxCloudFavorites(config.userHome());
        this.entry = entry;
        this.repaint = repaint;
        this.exit = exit;
        this.focus = 1; // Open on the primary action, not the close button.
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
        boolean selected = selectedFocus() == index;
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
        boolean selected = hits.size() == selectedFocus();
        rounded(g, x, y, w, h, 20*u, MenuView.CARD);
        rounded(g, x+12*u,y+12*u,w-24*u,Math.max(40*u,h*0.47f),14*u,
                selected ? MenuView.ACCENT : MenuView.dark ? new Color(47, 61, 80) : new Color(215,228,240));
        BufferedImage cover = cover(game);
        if (cover != null) {
            float availableW=w-34*u, availableH=Math.max(32*u,h*0.47f-14*u);
            float scale=Math.min(availableW/cover.getWidth(),availableH/cover.getHeight());
            int imageW=Math.max(1,Math.round(cover.getWidth()*scale));
            int imageH=Math.max(1,Math.round(cover.getHeight()*scale));
            g.drawImage(cover,Math.round(x+(w-imageW)/2f),Math.round(y+12*u+(availableH-imageH)/2f),imageW,imageH,null);
        } else {
            g.setColor(selected ? Color.WHITE : MenuView.TEXT);
            g.setFont(MenuView.font(Font.BOLD, 32*u));
            String initials = game.name().isBlank() ? "?" : game.name().trim().substring(0,1).toUpperCase(Locale.ROOT);
            center(g, initials, x+w/2f, y+Math.max(40*u,h*0.47f)*0.65f + 12*u);
        }
        g.setFont(MenuView.font(Font.BOLD, 17*u)); g.setColor(MenuView.TEXT);
        center(g, shortened(g, game.name(),w-24*u), x+w/2f, y+Math.max(40*u,h*0.47f)+43*u);
        g.setFont(MenuView.font(Font.PLAIN, 12*u));g.setColor(MenuView.TEXT_DIM);
        center(g, "A  Play   X  Pin", x+w/2f, y+h-16*u);
        if (selected) {
            g.setStroke(new BasicStroke(3*u));g.setColor(MenuView.ACCENT);
            g.draw(new RoundRectangle2D.Float(x-3*u,y-3*u,w+6*u,h+6*u,22*u,22*u));
        }
        favoriteTargets.put(hits.size(),game);
        hits.add(new Hit(new Rectangle2D.Float(x, y, w, h), () -> startGame(game), true));
        // The star is also pointer/touch accessible. Draw and register it
        // after the card, so the smaller hit target wins on mouse clicks.
        float star=30*u, sx=x+w-star-10*u, sy=y+10*u;
        rounded(g,sx,sy,star,star,star/2,MenuView.CARD);
        g.setColor(favorites.contains(game.id()) ? MenuView.ACCENT : MenuView.TEXT_DIM);
        g.setFont(MenuView.font(Font.BOLD,20*u));
        center(g,favorites.contains(game.id()) ? "★" : "☆",sx+star/2,sy+star*0.74f);
        favoriteTargets.put(hits.size(),game);
        hits.add(new Hit(new Rectangle2D.Float(sx,sy,star,star),()->toggleFavorite(game),false));
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
        favoriteTargets.clear();
        quickLaunchStart = quickLaunchEnd = -1;
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
            action(g,width-pad-300*u,26*u,172*u,48*u,
                    nativeRtc()?"RTC: Native (beta)":"RTC: Chromium",this::toggleNativeRtc,u,false);
        }
        if(searchEditing) paintSearch(g,width,height,u);
        if(searchEditing) searchFocus=hits.isEmpty()?0:Math.max(0,Math.min(searchFocus,hits.size()-1));
        else focus=hits.isEmpty()?0:Math.max(0,Math.min(focus,hits.size()-1));
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
                    "I've signed in",()->checkLogin(true),u,false);
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
        float bw=Math.min(230*u,(w-2*pad-3*gap)/4);
        action(g,pad,top,bw,45*u,"Search: "+(query.isBlank()?"All games":query),this::openSearch,u,false);
        action(g,pad+bw+gap,top,bw,45*u,"Reload",this::loadGames,u,false);
        action(g,pad+2*(bw+gap),top,bw,45*u,"Sign out",this::logout,u,false);
        action(g,pad+3*(bw+gap),top,bw,45*u,
                "Auto audio: "+(autoAudio()?"On":"Off"),this::toggleAutoAudio,u,false);
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
    /** Controller X marks or removes the currently highlighted catalog tile. */
    void toggleFocusedFavorite() {
        if(searchEditing)return;
        Game selected=favoriteTargets.get(focus);
        if(selected!=null)toggleFavorite(selected);
        else{status="Select a game tile, then press X to add it to Favorites.";repaint.run();}
    }

    private void toggleFavorite(Game game) {
        try {
            boolean added=favorites.toggle(game.id(),game.name(),game.cover());
            status=(added?"Added to":"Removed from")+" Home screen: "+game.name();
            if(added) {
                BufferedImage loaded=coverImages.get(game.cover());
                if(loaded!=null)coverLoader.execute(()->favorites.cacheCover(game.id(),loaded));
                else cover(game); // Download once; the loader will persist it on completion.
            }
            repaint.run();
        } catch(IOException error) {
            status="Could not update Home screen: "+error.getMessage();repaint.run();
        }
    }

    private void paintSearch(Graphics2D g,int w,int h,float u) {
        // One single UI surface; no JOptionPane or separate desktop window.
        paintingSearch=true;
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
        paintingSearch=false;
    }
    /** Trusted online art for catalog tiles; favorites prefer disk-cached PNGs. */
    private BufferedImage cover(Game game) {
        String url=game.cover()==null?"":game.cover();
        String key=url.isBlank()?"favorite:"+game.id():url;
        BufferedImage cached=coverImages.get(key);
        if(cached!=null)return cached;
        if(closed || !requestedCovers.add(key))return null;
        coverLoader.execute(()->{
            BufferedImage result=favorites.cachedCover(game.id());
            if(result==null && !url.isBlank()) {
                try { result=loadCover(URI.create(url)); } catch(Exception ignored) { }
                if(result!=null && favorites.contains(game.id()))
                    favorites.cacheCover(game.id(),result);
            }
            BufferedImage image=result;
            SwingUtilities.invokeLater(()->{
                if(closed)return;
                if(image!=null) {
                    if(coverImages.size()>=96) {
                        String old=coverImages.keySet().iterator().next();
                        coverImages.remove(old);
                        requestedCovers.remove(old);
                    }
                    coverImages.put(key,image);
                }
                repaint.run();
            });
        });
        return null;
    }
    private static BufferedImage loadCover(URI uri) throws IOException {
        if(!"https".equals(uri.getScheme()) ||
                !"store-images.s-microsoft.com".equals(uri.getHost()) ||
                uri.getRawUserInfo()!=null || uri.getPort()!=-1)
            throw new IOException("Untrusted Xbox cover image origin");
        HttpURLConnection connection=(HttpURLConnection)uri.toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(4500);connection.setReadTimeout(4500);
        connection.setRequestProperty("User-Agent","WII-UU-Xbox-Catalog");
        try {
            if(connection.getResponseCode()!=200 ||
                    connection.getContentLengthLong()>2*1024*1024)
                return null;
            try(InputStream input=connection.getInputStream();
                ByteArrayOutputStream output=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int n;
                while((n=input.read(buffer))!=-1){
                    if(output.size()+n>2*1024*1024)return null;
                    output.write(buffer,0,n);
                }
                try(ImageInputStream imageInput=ImageIO.createImageInputStream(
                        new ByteArrayInputStream(output.toByteArray()))) {
                    var readers=ImageIO.getImageReaders(imageInput);
                    if(!readers.hasNext())return null;
                    ImageReader reader=readers.next();
                    try {
                        reader.setInput(imageInput);
                        int w=reader.getWidth(0),h=reader.getHeight(0);
                        if(w<1||h<1||w>8192||h>8192)return null;
                        var params=reader.getDefaultReadParam();
                        int subsample=Math.max(1,Math.max(w/420,h/250));
                        params.setSourceSubsampling(subsample,subsample,0,0);
                        return reader.read(0,params);
                    } finally {reader.dispose();}
                }
            }
        } finally {connection.disconnect();}
    }

    private void typeLetter(char c) {
        if(query.length()<72){query+=c;page=0;repaint.run();}
    }
    private void erase() {
        if(!query.isEmpty()){query=query.substring(0,query.length()-1);page=0;repaint.run();}
    }
    private boolean nativeRtc() { return config.getBool("xcloud.nativeRtc", false); }
    private void toggleNativeRtc() {
        config.set("xcloud.nativeRtc", Boolean.toString(!nativeRtc()));
        config.save();
        status=nativeRtc()?"Experimental native RTC selected. Install the Java RTC runtime first.":"Chromium RTC selected.";
        repaint.run();
    }
    private boolean autoAudio() { return config.getBool("xcloud.audioAutoplay", true); }
    private void toggleAutoAudio() {
        config.set("xcloud.audioAutoplay", Boolean.toString(!autoAudio()));
        config.save();
        status="Xbox audio autoplay "+(autoAudio()?"enabled":"disabled")+
                ". Applies to the next stream.";
        repaint.run();
    }
    private void openSearch() {
        // Immediately invalidate the library's hit areas. InputRouter can
        // deliver queued stick repeat events before the overlay is repainted.
        searchEditing=true;hits.clear();searchFocus=0;repaint.run();
    }
    private void finishSearch() {
        searchEditing=false;hits.clear();focus=games.isEmpty()?1:5;page=0;repaint.run();
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
        int selection=searchEditing?searchFocus:focus;
        selection=Math.max(0,Math.min(selection,hits.size()-1));
        Rectangle2D current=hits.get(selection).area();
        double cx=current.getCenterX(),cy=current.getCenterY();
        double best=Double.MAX_VALUE;int chosen=-1;
        for(int n=0;n<hits.size();n++){
            if(n==selection)continue;
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
            if(dy!=0&&!searchEditing&&signedIn&&dy<0&&focus>4)page(-1);
            else if(dy!=0&&!searchEditing&&signedIn&&dy>0)page(1);
            else Sfx.bump();
            return;
        }
        if(searchEditing)searchFocus=chosen;
        else focus=chosen;
        Sfx.move();repaint.run();
    }
    void activate() {
        int selection=searchEditing?searchFocus:focus;
        if(hits.isEmpty()||selection<0||selection>=hits.size())return;
        Sfx.select();
        hits.get(selection).action().run();
    }
    void page(int delta) {
        if(!signedIn||searchEditing)return;
        int pages=Math.max(1,(filtered().size()+Math.max(1,pageSize)-1)/Math.max(1,pageSize));
        int next=Math.max(0,Math.min(pages-1,page+Integer.signum(delta)));
        if(next==page){Sfx.bump();return;}
        page=next;focus=5;Sfx.move();repaint.run();
    }
    void back() {
        if(searchEditing){finishSearch();return;}
        Sfx.back();exit.run();
    }
    void click(int x,int y) {
        for(int n=hits.size()-1;n>=0;n--)
            if(hits.get(n).area().contains(x,y)){
                if(searchEditing)searchFocus=n;
                else focus=n;
                activate();return;
            }
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
                // Refresh the bundled backend on upgrades without redownloading Chromium.
                XboxCloudRuntime.syncBundledService(config);
                Path user=Files.createDirectories(config.userHome().resolve("xcloud"));
                Backend next=new Backend(root,user,message->SwingUtilities.invokeLater(()->onMessage(message)));
                SwingUtilities.invokeLater(()->{
                    startingBackend=false;
                    if(closed){next.close();return;}
                    backend=next;status="Ready. Sign in with Microsoft to continue.";
                    command("signedIn",result->{
                        if(Boolean.TRUE.equals(result.get("signedIn"))) {
                            // The replacement helper has a fresh entitlement/catalog map.
                            // Always reload it even if the UI was already marked signed in.
                            if(signedIn) { if (!launchPendingFavorite()) loadGames(); }
                            else completeLogin();
                        } else if(!pairing) {
                            signedIn=false; // A new helper may no longer have valid account tokens.
                            status="Ready. Sign in with Microsoft to continue.";
                        }
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
            pairingStatusTimer.start();
            focus=1;
        }else if("signedIn".equals(type)){
            completeLogin();
        }else if("authError".equals(type)){
            pairing=false;pairingStatusTimer.stop();
            status="Microsoft sign-in failed: "+MiniJson.string(message.get("message"));
        }else if("streamEnded".equals(type)){
            // An Xbox-side disconnect cannot rely on window.close(), because
            // Chrome app windows launched by Java are not script-opened.
            if(playing()){stopGame();status="Xbox ended the stream. Choose another game.";}
        }else if("ended".equals(type)){
            if (playing()) stopGame();
            pairingStatusTimer.stop();
            backend=null;
            pairing=false;
            loginCode=""; // A pairing code belongs to the exited helper and cannot be polled.
            games.clear(); // The previous helper's playable-title IDs are no longer valid.
            status="The local Xbox service stopped. Select Reload to reconnect.";
        }
        repaint.run();
    }
    /** Microsoft authentication is completed before any Xbox Cloud entitlement check. */
    private void completeLogin() {
        if(signedIn)return;
        signedIn=true;
        pairing=false;
        pairingStatusTimer.stop();
        loginCode="";
        focus=0;
        status="Microsoft sign-in complete. Loading Xbox games...";
        if (!launchPendingFavorite()) loadGames();
    }

    private void checkLogin(boolean showPending) {
        if(closed || !pairing || backend==null)return;
        backend.call("signedIn").whenComplete((response,error)->SwingUtilities.invokeLater(()->{
            if(closed || !pairing)return;
            if(error!=null){
                if(showPending)status="Couldn't check Microsoft sign-in: "+error.getMessage();
            }else if(Boolean.TRUE.equals(response.get("signedIn"))){
                completeLogin();
            }else if(showPending){
                status="Microsoft has not finished authorizing this device yet. Waiting for approval...";
            }
            repaint.run();
        }));
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
                String cover=MiniJson.string(row.get("cover"));
                if(id!=null&&!id.isBlank()&&name!=null&&!name.isBlank())
                    games.add(new Game(id,name,cover==null?"":cover));
            }
            games.sort(Comparator.comparing(Game::name,String.CASE_INSENSITIVE_ORDER));
            for(Game game:games)favorites.update(game.id(),game.name(),game.cover());
            page=0;focus=5;
            status=games.size()+" playable games available.";
        });
    }
    private void logout() {
        if(playing())stopGame();
        command("logout",result->{
            signedIn=false;pairing=false;pairingStatusTimer.stop();
            loginCode="";games.clear();page=0;focus=1;query="";
            status="Signed out. Get a new Microsoft login code to continue.";
        });
    }
    /**
     * The isolated Xbox player must not open an OS-keyring password dialog.
     * On Raspberry Pi OS and Debian, Chromium otherwise asks to unlock GNOME
     * Keyring / KWallet, which is inaccessible using only a controller.
     * Do not change the user's normal browser or the system keyring.
     */
    static void configureBrowserPasswordStore(List<String> chromeCommand, String osName) {
        if (osName != null && osName.toLowerCase(Locale.ROOT).contains("linux"))
            chromeCommand.add("--password-store=basic");
    }

    private void startNativeRtc(Game chosen) throws IOException {
        NativeRtcSession session=NativeRtcSession.open(XboxCloudRuntime.home(config).resolve("java-rtc"),
                backend.port,backend.secret,chosen.id(),autoAudio(),controllerFrames);
        nativeSession=session;
        PluginStore.trackInProcess(config,entry,session::close,session.finished());
        setStreamActive(true);
        status="Starting "+chosen.name()+" using Java RTC.";
        session.finished().whenComplete((ignored,error)->SwingUtilities.invokeLater(()->{
            if(nativeSession!=session)return;
            nativeSession=null;
            setStreamActive(false);
            if(!closed){
                status=error==null?"Stream closed. Choose another game.":"Native RTC failed: "+error.getMessage();
                repaint.run();
            }
        }));
    }

    private void startGame(Game chosen) {
        if(closed||backend==null||!signedIn)return;
        String desiredTitleId=chosen.id();
        Game available=games.stream().filter(game->game.id().equals(desiredTitleId)).findFirst().orElse(null);
        if(available==null && !favorites.contains(desiredTitleId)) {
            status="This favorite is not currently playable. Reload the Xbox catalog.";
            repaint.run();
            return;
        }
        if (available != null) chosen=available;
        if(playing()){status="A cloud stream is already running.";repaint.run();return;}
        boolean useNative=nativeRtc();
        try {
            if(useNative) {
                startNativeRtc(chosen);
                repaint.run();return;
            }
            ProcessBuilder builder;
            String url="http://127.0.0.1:"+backend.port+
                    "/?title="+URLEncoder.encode(chosen.id(),StandardCharsets.UTF_8)+
                    "&autoAudio="+(autoAudio()?"1":"0")+
                    "&bindings="+URLEncoder.encode(XboxKeyboardMap.serialized(config),StandardCharsets.UTF_8)+
                    "&sensitivity="+XboxKeyboardMap.sensitivity(config)+"#"+backend.secret;
            Path profile=Files.createDirectories(config.userHome().resolve("xcloud/chrome-profile"));
            builder=new ProcessBuilder(
                    XboxCloudRuntime.chromium(config).toString(),
                    "--user-data-dir="+profile.toAbsolutePath(),
                    "--app="+url,"--no-first-run","--disable-sync","--disable-background-networking",
                    "--disable-infobars");
            // In Linux desktop sessions, Chromium may prompt for the system
            // password keyring. Use the basic local store ONLY for this isolated
            // Xbox WebRTC player; Microsoft credentials live in the Node helper.
            configureBrowserPasswordStore(builder.command(), System.getProperty("os.name", ""));
            // This isolated browser only serves the local player. Permit
            // playback without a mouse gesture when the user opts in.
            if(autoAudio())builder.command().add("--autoplay-policy=no-user-gesture-required");
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            setStreamActive(true);
            Process process=builder.start();
            browser=process;
            PluginStore.trackExternal(config,entry,process);
            startControllerForwarding();
            status="Starting "+chosen.name()+" using "+(useNative?"native RTC":"Chromium")+". Return here to select another game.";
            process.onExit().thenRun(()->SwingUtilities.invokeLater(()->{
                if(browser==process){browser=null;stopControllerForwarding();setStreamActive(false);if(!closed) {
                    status="Stream closed. Choose another game.";
                    command("stop",result->repaint.run());
                    repaint.run();
                }}
            }));
        }catch(Exception error){
            if (playing()) stopGame();
            else setStreamActive(false);
            status="Could not start "+(useNative?"native RTC":"Chromium")+": "+error.getMessage();
        }
        repaint.run();
    }
    private void startControllerForwarding() {
        if (controllerTask != null) controllerTask.cancel(false);
        controllerTask = controllerSender.scheduleAtFixedRate(() -> {
            try {
                Backend target = backend;
                Process running = browser;
                if (closed || target == null || running == null || !running.isAlive()) return;
                target.sendControllers(controllerFrames.get());
            } catch (RuntimeException | IOException error) {
                // Player shutdown and controller disconnects must not stop streaming.
            }
        }, 0, 33, TimeUnit.MILLISECONDS);
    }

    private void stopControllerForwarding() {
        if (controllerTask != null) controllerTask.cancel(false);
        controllerTask = null;
    }

    private void stopGame() {
        if(nativeSession!=null){nativeSession.close();status="Stopping Java RTC...";repaint.run();return;}
        stopControllerForwarding();
        Process p=browser;
        if(p!=null&&p.isAlive()){p.descendants().forEach(ProcessHandle::destroy);p.destroy();}
        browser=null;
        setStreamActive(false);
        command("stop",result->{status="Xbox stream stopped.";});
    }
    @Override public void close() {
        if(closed)return;
        closed=true;
        if(nativeSession!=null)nativeSession.close();
        pairingStatusTimer.stop();
        stopControllerForwarding();
        controllerSender.shutdownNow();
        Process p=browser;
        if(p!=null&&p.isAlive()){p.descendants().forEach(ProcessHandle::destroy);p.destroy();}
        browser=null;
        setStreamActive(false);
        if(backend!=null)backend.close();
        hits.clear();games.clear();
        coverLoader.shutdownNow();coverImages.clear();requestedCovers.clear();
        favoriteTargets.clear();
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

        /** One-way private IPC update; controller frames contain no user identity. */
        synchronized void sendControllers(List<Map<String, Object>> frames) throws IOException {
            writer.write("{\"event\":\"managedPads\",\"pads\":" +
                    wiiuu.net.XboxCloudPadBridge.json(frames) + "}\n");
            writer.flush();
        }

        @Override public void close() {
            process.destroy();
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
