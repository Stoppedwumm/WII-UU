package wiiuu.ui;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.jar.*;
import javax.tools.ToolProvider;

/** No native library or Xbox credentials required. Exercises real classloader boundary. */
public final class NativeRtcSessionTest {
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("rtc-in-jvm-test");
        Files.createDirectories(root.resolve("dependency"));
        Path source=root.resolve("NativeRtcPlayer.java");
        Files.writeString(source,"""
            package wiiuu.xcloud;
            import java.util.*;
            import java.util.function.*;
            import java.util.concurrent.*;
            public class NativeRtcPlayer {
                public static Runnable open(int port,String secret,String title,boolean audio,
                    Supplier<List<Map<String,Object>>> frames,CompletableFuture<Void> finished,
                    Supplier<javax.swing.JComponent> loadingFactory){
                    if(port!=1234 || !title.equals("fixture") || !audio ||
                       !frames.get().get(0).get("pid").equals(ProcessHandle.current().pid()))
                        throw new AssertionError("Not running in original JVM");
                    if(!"mascot-fixture".equals(loadingFactory.get().getName()))
                        throw new AssertionError("Loading panel factory did not cross classloader");
                    return () -> finished.complete(null);
                }
            }
            """);
        if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",root.toString(),source.toString())!=0)
            throw new AssertionError("Fixture compilation");
        String name="wiiuu/xcloud/NativeRtcPlayer.class";
        try(JarOutputStream jar=new JarOutputStream(Files.newOutputStream(root.resolve("java-rtc.jar")))){
            jar.putNextEntry(new JarEntry(name));Files.copy(root.resolve(name),jar);jar.closeEntry();
        }
        for(int i=0;i<2;i++){
            NativeRtcSession session=NativeRtcSession.open(root,1234,"fixture","fixture",true,()->List.of(Map.of("pid",ProcessHandle.current().pid())),()->{
                javax.swing.JPanel panel=new javax.swing.JPanel();panel.setName("mascot-fixture");return panel;
            });
            try {
                NativeRtcSession.open(root,1234,"fixture","fixture",true,List::of,javax.swing.JPanel::new);
                throw new AssertionError("Overlapping session accepted");
            }catch(java.io.IOException expected){}
            session.close();session.finished().get(5,TimeUnit.SECONDS);
        }
        System.out.println("In-JVM Java RTC lifecycle passed");
    }
}
