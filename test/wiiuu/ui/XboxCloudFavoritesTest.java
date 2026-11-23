package wiiuu.ui;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.List;

/** Offline favorites durability, image caching, and profile-isolation tests. */
public final class XboxCloudFavoritesTest {
    private static final String COVER =
        "https://store-images.s-microsoft.com/image/apps.12345.12345678901234567.jpg";
    private static void check(boolean value,String reason) {
        if(!value)throw new AssertionError(reason);
    }
    public static void main(String[] args) throws Exception {
        Path user=Files.createTempDirectory("xcloud-favorites-");
        XboxCloudFavorites favorites=new XboxCloudFavorites(user);
        check(favorites.all().isEmpty(),"Starts empty");
        check(favorites.toggle("1234:ABC","Game \"One\" \n& Sons",COVER),"Add a title");
        check(favorites.contains("1234:ABC"),"Contains the favorite");
        Path json=user.resolve("xcloud/favorites.json");
        check(Files.isRegularFile(json),"Persisted on disk");
        String body=Files.readString(json,StandardCharsets.UTF_8);
        check(body.contains("\\n") && body.contains("\\\""),"JSON escaped correctly");
        XboxCloudFavorites restarted=new XboxCloudFavorites(user);
        check(restarted.all().size()==1,"Favorites survive process restart");
        check(restarted.get("1234:ABC").name().equals("Game \"One\" \n& Sons"),"Name round-trip");
        restarted.update("1234:ABC","Renamed",COVER);
        check(new XboxCloudFavorites(user).get("1234:ABC").name().equals("Renamed"),
                "Catalog metadata refresh is persistent");
        BufferedImage image=new BufferedImage(140,84,BufferedImage.TYPE_INT_RGB);
        restarted.cacheCover("1234:ABC",image);
        XboxCloudFavorites offline=new XboxCloudFavorites(user);
        BufferedImage diskImage=offline.cachedCover("1234:ABC");
        check(diskImage!=null && diskImage.getWidth()==140 && diskImage.getHeight()==84,
                "Cached cover available offline on a fresh instance");
        check(offline.toggle("1234:ABC","Renamed",COVER)==false,"Remove favorite");
        check(!new XboxCloudFavorites(user).contains("1234:ABC"),"Removal persists");
        check(offline.cachedCover("1234:ABC")==null,"Removed favorite has no cached cover");
        offline.cacheCover("1234:ABC",image);
        try(var stream=Files.list(user.resolve("xcloud/favorite-covers"))) {
            check(stream.noneMatch(p->p.getFileName().toString().endsWith(".png")),
                    "Removed favorite leaves no cached files");
        }

        for(String invalid:List.of("http://store-images.s-microsoft.com/a",
                "https://store-images.s-microsoft.com:444/a",
                "https://store-images.s-microsoft.com@evil.example/a",
                "https://example.com/cover.png")) {
            boolean rejected=false;
            try { offline.toggle("BAD","Invalid",invalid); }
            catch(java.io.IOException expected){rejected=true;}
            check(rejected,"Reject untrusted cover URL "+invalid);
        }
        boolean rejected=false;
        try { offline.toggle("BAD/../../path","Invalid",COVER); }
        catch(java.io.IOException expected){rejected=true;}
        check(rejected,"Reject unsafe identifiers");
        check(offline.all().isEmpty(),"Rejected records cannot be persisted");
        Path second=Files.createTempDirectory("xcloud-favorites-another-profile-");
        check(new XboxCloudFavorites(second).all().isEmpty(),"No cross-user favorites leakage");

        // A malformed or very large record file cannot make the UI crash.
        Files.writeString(json,"[}]",StandardCharsets.UTF_8);
        check(new XboxCloudFavorites(user).all().isEmpty(),"Malformed JSON ignored");
        Files.writeString(json,"x".repeat(150000),StandardCharsets.UTF_8);
        check(new XboxCloudFavorites(user).all().isEmpty(),"Oversized JSON ignored");

        System.out.println("Xbox Cloud favorites persist safely and cache covers offline");
    }
}
