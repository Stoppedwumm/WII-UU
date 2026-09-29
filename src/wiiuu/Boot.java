package wiiuu;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Start-up class of the macOS WII-UU.app: loads WII-UU from the installed wiiuu.jar instead of a
 * copy inside the app.
 *
 * <p>macOS ties the Accessibility (GamePad keys) and Screen Recording (GamePad screen) permissions
 * to the app's code signature. An app rebuilt on every update gets a new signature, and the
 * permissions, still shown as on in System Settings, silently stop applying. With this class the
 * app stays the same across updates; only wiiuu.jar next to it changes. It is the only class in
 * the app's own jar (see install.sh), so everything else comes from wiiuu.jar.
 */
public final class Boot {
    private Boot() {}

    public static void main(String[] args) throws Exception {
        String custom = System.getenv("WIIUU_JAR");
        File jar = custom != null && !custom.isBlank() ? new File(custom)
                : new File(System.getProperty("user.home"), "Library/Application Support/WII-UU/wiiuu.jar");
        if (!jar.isFile()) {
            javax.swing.JOptionPane.showMessageDialog(null, "WII-UU is not installed (" + jar + " is missing).\n"
                    + "Run the installer again.", "WII-UU", javax.swing.JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
        // the platform loader as parent: WII-UU's classes must all come from wiiuu.jar, not from this app
        URLClassLoader loader = new URLClassLoader(new URL[]{jar.toURI().toURL()}, ClassLoader.getPlatformClassLoader());
        Thread.currentThread().setContextClassLoader(loader);
        System.setProperty("wiiuu.app", "1");
        Method main = loader.loadClass("wiiuu.Main").getMethod("main", String[].class);
        main.invoke(null, (Object) args);
    }
}
