package wiiuu.ui;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import wiiuu.core.Config;
import wiiuu.core.PluginStore;

/** Offline full-screen native xCloud TV/GamePad styling and controller navigation fixture. */
public final class XboxCloudNativeScreenTest {
    private static void ok(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static Object field(Object object, String name) throws Exception {
        Field f=object.getClass().getDeclaredField(name); f.setAccessible(true);return f.get(object);
    }
    private static void set(Object object,String name,Object value) throws Exception {
        Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value);
    }
    private static void render(XboxCloudNativeScreen screen,int w,int h,String path) throws Exception {
        BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        screen.paint(graphics,w,h);
        graphics.dispose();
        ok(image.getRGB(w/2,h/2)!=0,"Native UI must paint its screen");
        Files.createDirectories(Path.of("build"));
        ImageIO.write(image,"png",Path.of("build",path).toFile());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void main(String[] args) throws Exception {
        Sfx.setEnabled(false);
        Config config=new Config(Files.createTempDirectory("xcloud-menu-ui-"));
        PluginStore.Entry entry=new PluginStore.Entry("xcloud","Xbox Cloud Gaming","","","external");
        AtomicInteger repaint=new AtomicInteger(),exits=new AtomicInteger();
        XboxCloudNativeScreen screen=new XboxCloudNativeScreen(config,entry,repaint::incrementAndGet,exits::incrementAndGet);
        ok(!javax.swing.JFrame.class.isAssignableFrom(XboxCloudNativeScreen.class),
                "xCloud must be embedded in WII-UU, not a separate JFrame");

        // Debian and Raspberry Pi OS can request a GNOME Keyring / KWallet
        // password when Chromium starts. Avoid unclosable native dialogs only
        // for the isolated Xbox browser; preserve other platforms' defaults.
        for(String os : java.util.List.of("Linux","linux","GNU/Linux")) {
            java.util.List<String> argsChrome=new java.util.ArrayList<>(java.util.List.of("chrome","--app=http://127.0.0.1"));
            XboxCloudNativeScreen.configureBrowserPasswordStore(argsChrome,os);
            ok(java.util.Collections.frequency(argsChrome,"--password-store=basic")==1,
                    "Linux Xbox Chromium must bypass the system keyring: "+os);
            ok(!argsChrome.contains("--use-mock-keychain"),
                    "macOS-only mock keychain switch is not the Linux fix");
        }
        for(String os : java.util.List.of("Mac OS X","Windows 11","FreeBSD","")) {
            java.util.List<String> argsChrome=new java.util.ArrayList<>(java.util.List.of("chrome"));
            XboxCloudNativeScreen.configureBrowserPasswordStore(argsChrome,os);
            ok(!argsChrome.contains("--password-store=basic") &&
                    !argsChrome.contains("--use-mock-keychain"),
                    "Non-Linux Xbox Chromium must retain default password-store behavior: "+os);
        }
        java.util.List<String> nilOsCommand=new java.util.ArrayList<>(java.util.List.of("chrome"));
        XboxCloudNativeScreen.configureBrowserPasswordStore(nilOsCommand,null);
        ok(!nilOsCommand.contains("--password-store=basic"),"Null OS name must be safe");

        MenuView.applyTheme(false);
        render(screen,1280,800,"xcloud-ui-install-light.png");
        MenuView.applyTheme(true);
        render(screen,640,400,"xcloud-ui-install-small-dark.png");

        // Simulate Microsoft device-code approval before an Xbox entitlement lookup.
        // The native UI must leave pairing even when Xbox game servers are unavailable.
        var onMessage=XboxCloudNativeScreen.class.getDeclaredMethod("onMessage",java.util.Map.class);
        onMessage.setAccessible(true);
        onMessage.invoke(screen,java.util.Map.of("event","code","code","OFFLINE","url","https://www.microsoft.com/link"));
        ok((boolean)field(screen,"pairing"),"Pairing must show the Microsoft code");
        onMessage.invoke(screen,java.util.Map.of("event","signedIn"));
        ok((boolean)field(screen,"signedIn"),"Microsoft approval should mark account signed in");
        ok(!(boolean)field(screen,"pairing"),"Microsoft approval should leave the pairing screen");
        ok(((String)field(screen,"loginCode")).isEmpty(),"Do not keep showing the old code");
        // When the helper dies, its pending code and cached game IDs must be dropped.
        onMessage.invoke(screen,java.util.Map.of("event","code","code","STALE","url","https://www.microsoft.com/link"));
        onMessage.invoke(screen,java.util.Map.of("event","ended"));
        ok(!(boolean)field(screen,"pairing"),"An exited backend must not leave a dead pairing code");
        ok(((String)field(screen,"loginCode")).isEmpty(),"The exited backend code must be cleared");
        // Return to the signed-out state for the screenshot fixtures.
        set(screen,"signedIn",false);
        set(screen,"pairing",false);

        // Simulate the installed runtime using empty local files. No network or Node
        // process is started, since the screen was constructed before this fixture.
        Path runtime=wiiuu.core.XboxCloudRuntime.home(config);
        Files.createDirectories(runtime.resolve("node_modules/xal-node"));
        Files.writeString(runtime.resolve("node_modules/xal-node/package.json"),"{}");
        Files.writeString(runtime.resolve("player.bundle.js"),"fixture");
        Path chrome=wiiuu.core.XboxCloudRuntime.chromium(config);
        Files.createDirectories(chrome.getParent());
        Files.writeString(chrome,"fixture");
        Files.writeString(runtime.resolve("installed-version"),"native-v1");
        ok(wiiuu.core.XboxCloudRuntime.ready(config),"Fixture must enable the catalog renderer");

        set(screen,"pairing",true);
        set(screen,"loginCode","TEST-CODE");
        render(screen,1280,800,"xcloud-ui-auth-waiting.png");
        set(screen,"pairing",false);

        // Supply catalog fixture without live Xbox credentials or network requests.
        set(screen,"signedIn",true);
        Class<?> gameClass=Arrays.stream(XboxCloudNativeScreen.class.getDeclaredClasses())
                .filter(k->k.getSimpleName().equals("Game")).findFirst().orElseThrow();
        Constructor<?> constructor=gameClass.getDeclaredConstructor(String.class,String.class,String.class);
        constructor.setAccessible(true);
        java.util.List games=(java.util.List)field(screen,"games");
        for(int i=0;i<26;i++) games.add(constructor.newInstance("GAME"+i,"Sample Game "+i,""));
        MenuView.applyTheme(false);
        render(screen,1280,800,"xcloud-ui-catalog-light.png");
        // Controller X toggles favorites, and the quick-launch shelf is part
        // of the same input navigation surface as the standard Xbox catalog.
        set(screen,"focus",5); // First game after Close + the four catalog controls.
        screen.toggleFocusedFavorite();
        XboxCloudFavorites savedFavorites=new XboxCloudFavorites(config.userHome());
        ok(savedFavorites.all().size()==1 && savedFavorites.contains("GAME0"),
                "Controller X favorites the selected catalog game and persists it");
        render(screen,1280,800,"xcloud-ui-catalog-favorites.png");
        int quick=(int)field(screen,"quickLaunchStart");
        ok(quick==-1, "Favorites shelf is now on the home screen");
        screen.launchFavorite(savedFavorites.get("GAME0"));
        ok(field(screen,"pendingFavorite")!=null, "Pinned launch waits for sign-in, not catalog");
        set(screen,"focus",5); // First catalog card.
        screen.toggleFocusedFavorite();
        ok(new XboxCloudFavorites(config.userHome()).all().isEmpty(),
                "X removes a favorite directly from the quick-launch shelf");
        render(screen,1280,800,"xcloud-ui-catalog-light.png");
        ok(config.getBool("xcloud.audioAutoplay",true),"Xbox audio should default to autoplay");
        screen.click(840,165); // The controller-selectable Auto audio toggle
        ok(!config.getBool("xcloud.audioAutoplay",true),"Native audio setting can disable autoplay");
        screen.click(840,165);
        ok(config.getBool("xcloud.audioAutoplay",false),"Native audio setting persists and re-enables autoplay");
        screen.navigate(0,1);
        screen.navigate(1,0);
        screen.page(1);
        render(screen,1280,800,"xcloud-ui-catalog-page2.png");
        ok((int)field(screen,"page")==1,"Gamepad L/R navigation must advance the game page");
        screen.page(-1);
        render(screen,640,400,"xcloud-ui-catalog-small.png");

        // Responsive search with keyboard and on-screen controller keys.
        render(screen,1280,800,"xcloud-ui-catalog-before-search.png");
        screen.click(50,160); // Search action at the top of the native catalog
        ok(screen.wantsKeys(),"Search must open the integrated on-screen keyboard");
        int underlyingFocus=(int)field(screen,"focus");
        ok(((java.util.List<?>)field(screen,"hits")).isEmpty(),
                "Opening the search overlay immediately invalidates library controls");
        screen.navigate(1,0);screen.activate(); // Before the overlay's next repaint
        ok("".equals(field(screen,"query")),
                "A queued stick event must not activate the background catalog");

        screen.keyTyped('S');screen.keyTyped('O');screen.keyTyped('N');
        ok("SON".equals(field(screen,"query")),"Physical keyboard must write into native search");
        screen.keyPressed(new KeyEvent(new JPanel(),KeyEvent.KEY_PRESSED,0,0,KeyEvent.VK_BACK_SPACE,'\b'));
        ok("SO".equals(field(screen,"query")),"Delete must edit the filter");
        render(screen,1280,800,"xcloud-ui-search-keyboard.png");
        render(screen,640,400,"xcloud-ui-search-keyboard-small.png");
        screen.navigate(1,0);screen.activate(); // On-screen controller key types a character
        ok((int)field(screen,"focus")==underlyingFocus,
                "Navigating the search keyboard must not change background catalog focus");
        ok((int)field(screen,"searchFocus")!=0,
                "Search keyboard must have its own controller selection");
        ok(((String)field(screen,"query")).length()>2,"Controller activation must type using the on-screen keyboard");
        screen.back();
        ok(((java.util.List<?>)field(screen,"hits")).isEmpty(),
                "Closing the keyboard invalidates stale overlay hit regions");
        ok(!screen.wantsKeys(),"B must close search keyboard without leaving the application");
        ok(exits.get()==0,"Closing search must not close the application");

        AtomicInteger musicChanges = new AtomicInteger();
        screen.onStreamActivity(musicChanges::incrementAndGet);
        var streamState = XboxCloudNativeScreen.class.getDeclaredMethod("setStreamActive", boolean.class);
        streamState.setAccessible(true);
        streamState.invoke(screen, true);
        ok(screen.streamActive() && musicChanges.get()==1, "Starting Xbox must suppress menu music");
        streamState.invoke(screen, true);
        ok(musicChanges.get()==1, "Repeated stream state must not restart audio");
        var stop = XboxCloudNativeScreen.class.getDeclaredMethod("stopGame");
        stop.setAccessible(true);
        stop.invoke(screen);
        ok(!screen.streamActive() && musicChanges.get()==2, "Stopping Xbox must restore menu music policy");
        streamState.invoke(screen, true);
        screen.close();
        ok(!screen.streamActive() && musicChanges.get()==4, "Closing Xbox must restore menu music policy");
        Files.delete(runtime.resolve("installed-version")); // prevent launching the mock backend
        MenuView menu=new MenuView(config,true);
        var open=MenuView.class.getDeclaredMethod("openXboxCloud",PluginStore.Entry.class);
        open.setAccessible(true);
        open.invoke(menu,entry);
        Field panelField=MenuView.class.getDeclaredField("xcloudPanel");
        panelField.setAccessible(true);
        ok(panelField.get(menu)!=null,"Dock integration must create the embedded Xbox Cloud screen");
        menu.back();
        ok(panelField.get(menu)==null,"B must restore the normal WII-UU home screen");
        System.out.println("Xbox Cloud Gaming renders responsive theme-aware TV/GamePad screens and native controller search");
    }
}
