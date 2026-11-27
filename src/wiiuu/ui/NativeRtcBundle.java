package wiiuu.ui;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.net.URL;

/** Extracts only the selected platform's immutable, bundled runtime. */
final class NativeRtcBundle {
    private static final String PREFIX="/xcloud/java-rtc/";
    private static final String VERSION="1.0.1";
    private record Entry(String platform,String hash,String name) {}

    static String platform(String os,String arch)throws IOException{
        os=os.toLowerCase(Locale.ROOT);arch=arch.toLowerCase(Locale.ROOT);
        String cpu=switch(arch){
            case "amd64","x86_64","x86-64","x64" -> "x86_64";
            case "aarch64","arm64" -> "aarch64";
            default -> throw new IOException("No bundled Java RTC for CPU "+arch);
        };
        if(os.contains("linux"))return "linux-"+cpu;
        if(os.contains("mac"))return "macos-"+cpu;
        if(os.contains("win")&&cpu.equals("x86_64"))return "windows-"+cpu;
        throw new IOException("No bundled Java RTC for "+os+" / "+arch);
    }

    private static String hash(byte[] bytes)throws IOException{
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
    }

    private static String hash(Path path)throws IOException{
        try(InputStream in=Files.newInputStream(path)){
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            byte[] b=new byte[65536];int n;
            while((n=in.read(b))!=-1)md.update(b,0,n);
            return HexFormat.of().formatHex(md.digest());
        }catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
    }

    static boolean present(){return NativeRtcBundle.class.getResource(PREFIX+"index.txt")!=null;}

    static List<URL> extract(Path root)throws IOException{
        String platform=platform(System.getProperty("os.name",""),System.getProperty("os.arch",""));
        byte[] index;
        try(InputStream in=NativeRtcBundle.class.getResourceAsStream(PREFIX+"index.txt")){
            if(in==null)throw new IOException("Java RTC bundle missing");
            index=in.readNBytes(65537);
            if(index.length>65536)throw new IOException("RTC bundle index too large");
        }
        List<Entry> selected=new ArrayList<>();
        Set<String> names=new HashSet<>();
        String expectedNative="webrtc-java-"+VERSION+"-"+platform+".jar";
        for(String row:new String(index,StandardCharsets.UTF_8).split("\\R")){
            if(row.isBlank())continue;
            String[] f=row.split(" ");
            if(f.length!=3||!f[1].matches("[a-f0-9]{64}")||!f[2].matches("[A-Za-z0-9][A-Za-z0-9._-]{0,180}\\.jar")||!names.add(f[2]))
                throw new IOException("Invalid bundled RTC index");
            if(f[0].equals("common")||f[0].equals(platform))selected.add(new Entry(f[0],f[1],f[2]));
        }
        Set<String> available=new HashSet<>();for(Entry e:selected)available.add(e.name());
        if(!available.containsAll(List.of("java-rtc.jar","webrtc-java-"+VERSION+".jar",
                "webrtc-java-swing-"+VERSION+".jar","gson-2.13.2.jar",expectedNative)))
            throw new IOException("Incomplete bundled Java RTC for "+platform);
        // Content-addressed cache: new builds do not overwrite JARs held open
        // by a different JVM, especially on Windows.
        Path cache=Files.createDirectories(root.resolve("bundled-"+hash(index)).resolve(platform));
        List<URL> urls=new ArrayList<>();
        for(Entry e:selected){
            Path target=cache.resolve(e.name());
            if(!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||!e.hash().equals(hash(target))){
                Path temp=Files.createTempFile(cache,".rtc-",".jar");
                try{
                    try(InputStream in=NativeRtcBundle.class.getResourceAsStream(PREFIX+e.name())){
                        if(in==null)throw new IOException("Missing bundled RTC artifact "+e.name());
                        Files.copy(in,temp,StandardCopyOption.REPLACE_EXISTING);
                    }
                    if(!e.hash().equals(hash(temp)))throw new IOException("Corrupt bundled RTC artifact "+e.name());
                    try{Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
                    catch(AtomicMoveNotSupportedException ex){Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING);}
                }finally{Files.deleteIfExists(temp);}
            }
            urls.add(target.toUri().toURL());
        }
        return List.copyOf(urls);
    }
}
