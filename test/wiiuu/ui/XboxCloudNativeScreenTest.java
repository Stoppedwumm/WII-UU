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
        screen.keyTyped('S');screen.keyTyped('O');screen.keyTyped('N');
        ok("SON".equals(field(screen,"query")),"Physical keyboard must write into native search");
        screen.keyPressed(new KeyEvent(new JPanel(),KeyEvent.KEY_PRESSED,0,0,KeyEvent.VK_BACK_SPACE,'\b'));
        ok("SO".equals(field(screen,"query")),"Delete must edit the filter");
        render(screen,1280,800,"xcloud-ui-search-keyboard.png");
        render(screen,640,400,"xcloud-ui-search-keyboard-small.png");
        screen.navigate(1,0);screen.activate(); // On-screen controller key types a character
        ok(((String)field(screen,"query")).length()>2,"Controller activation must type using the on-screen keyboard");
        screen.back();
        ok(!screen.wantsKeys(),"B must close search keyboard without leaving the application");
        ok(exits.get()==0,"Closing search must not close the application");

        screen.close();
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
