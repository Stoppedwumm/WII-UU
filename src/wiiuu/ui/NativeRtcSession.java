package wiiuu.ui;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Keeps one native library classloader per JVM. Reloading JNI under another
 * classloader can fail or crash, so updating the optional module needs a restart. */
final class NativeRtcSession implements AutoCloseable {
    private static URLClassLoader loader;
    private static Path loadedRoot;
    private static CompletableFuture<Void> active;
    private final Runnable stop;
    private final CompletableFuture<Void> finished;
    private NativeRtcSession(Runnable stop,CompletableFuture<Void> finished){
        this.stop=stop;this.finished=finished;
    }
    static synchronized NativeRtcSession open(Path root,int port,String secret,String title,boolean audio,
            Supplier<List<Map<String,Object>>> frames)throws IOException {
        if(active!=null&&!active.isDone())throw new IOException("Another Java RTC session is still stopping");
        root=root.toAbsolutePath().normalize();
        if(loader!=null&&!root.equals(loadedRoot))throw new IOException("Restart WII-UU after moving the RTC runtime");
        if(loader==null){
            Path jar=root.resolve("java-rtc.jar"),dependencies=root.resolve("dependency");
            if(!Files.isRegularFile(jar)||!Files.isDirectory(dependencies))throw new IOException("Java RTC not installed (see docs/xbox-cloud.md)");
            List<URL> urls=new ArrayList<>();
            urls.add(jar.toUri().toURL());
            try(var files=Files.list(dependencies)){
                for(Path file:files.filter(p->Files.isRegularFile(p)&&p.getFileName().toString().endsWith(".jar")).sorted().toList())urls.add(file.toUri().toURL());
            }
            loader=new URLClassLoader(urls.toArray(URL[]::new),NativeRtcSession.class.getClassLoader());
            loadedRoot=root;
        }
        try{
            Class<?> type=Class.forName("wiiuu.xcloud.NativeRtcPlayer",true,loader);
            CompletableFuture<Void> done=new CompletableFuture<>();
            Runnable stop=(Runnable)type.getMethod("open",int.class,String.class,String.class,boolean.class,Supplier.class,CompletableFuture.class)
                    .invoke(null,port,secret,title,audio,frames,done);
            active=done;
            return new NativeRtcSession(stop,done);
        }catch(ReflectiveOperationException|LinkageError e){
            throw new IOException("Cannot load Java RTC runtime; rebuild it and restart WII-UU",e);
        }
    }
    CompletableFuture<Void> finished(){return finished;}
    public void close(){stop.run();}
}
