package wiiuu.ui;

import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Exercise the actual embedded jar, not a manually installed Maven module. */
public final class NativeRtcBundleTest {
    public static void main(String[] args)throws Exception{
        if(!NativeRtcBundle.present())throw new AssertionError("RTC bundle missing from wiiuu.jar");
        if(!NativeRtcBundle.platform("Linux","aarch64").equals("linux-aarch64")
                ||!NativeRtcBundle.platform("Mac OS X","arm64").equals("macos-aarch64")
                ||!NativeRtcBundle.platform("Windows 11","amd64").equals("windows-x86_64"))
            throw new AssertionError("Platform selection");
        Path root=Files.createTempDirectory("wiiuu-rtc-bundle");
        List<URL> urls=NativeRtcBundle.extract(root);
        if(urls.size()<5)throw new AssertionError("Incomplete extraction");
        String nativeName="webrtc-java-1.0.1-"+NativeRtcBundle.platform(System.getProperty("os.name"),System.getProperty("os.arch"))+".jar";
        if(urls.stream().noneMatch(u->u.getPath().endsWith("/"+nativeName)))throw new AssertionError("Missing selected native jar");
        if(urls.stream().filter(u->u.getPath().matches(".*webrtc-java-1\\.0\\.1-(linux|macos|windows)-.*\\.jar")).count()!=1)
            throw new AssertionError("Extracted other platform libraries");
        Path module=urls.stream().filter(u->u.getPath().endsWith("/java-rtc.jar")).findFirst().map(u->{try{return Path.of(u.toURI());}catch(Exception e){throw new RuntimeException(e);}}).orElseThrow();
        Files.writeString(module,"corrupt");
        if(!NativeRtcBundle.extract(root).equals(urls)||Files.size(module)<1000)
            throw new AssertionError("Bundle did not repair corrupted cache");
        try(URLClassLoader loader=new URLClassLoader(urls.toArray(URL[]::new),NativeRtcBundleTest.class.getClassLoader())){
            Class<?> player=Class.forName("wiiuu.xcloud.NativeRtcPlayer",true,loader);
            player.getMethod("main",String[].class).invoke(null,(Object)new String[]{"--self-test"});
        }
        System.out.println("Packaged Java RTC extraction and protocol tests passed");
    }
}
