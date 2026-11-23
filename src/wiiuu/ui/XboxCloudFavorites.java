package wiiuu.ui;

import wiiuu.core.MiniJson;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-user Xbox favorites and disk-cached covers. Never stores account tokens. */
final class XboxCloudFavorites {
    record Favorite(String id, String name, String cover) {}
    static final int MAX_FAVORITES = 64;
    private static final int MAX_COVER_BYTES = 1_500_000;
    private final Path directory, dataFile, covers;
    private final Map<String, Favorite> entries = new LinkedHashMap<>();

    XboxCloudFavorites(Path userHome) {
        directory = userHome.resolve("xcloud");
        dataFile = directory.resolve("favorites.json");
        covers = directory.resolve("favorite-covers");
        load();
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[a-zA-Z0-9_-]{1,100}");
    }

    private static boolean validCover(String url) {
        if (url == null || url.isEmpty()) return true;
        if (url.length() > 2048) return false;
        try {
            java.net.URI uri = java.net.URI.create(url);
            return "https".equals(uri.getScheme())
                && "store-images.s-microsoft.com".equals(uri.getHost())
                && uri.getPort() == -1 && uri.getRawUserInfo() == null;
        } catch (IllegalArgumentException error) { return false; }
    }

    private void load() {
        try {
            if (!Files.isRegularFile(dataFile) || Files.size(dataFile) > 128_000) return;
            for (Object obj : MiniJson.array(MiniJson.parse(Files.readString(dataFile, StandardCharsets.UTF_8)))) {
                Map<String, Object> entry = MiniJson.object(obj);
                String id = MiniJson.string(entry.get("id"));
                String name = MiniJson.string(entry.get("name"));
                String cover = MiniJson.string(entry.get("cover"));
                if (!validId(id) || name == null || name.isBlank() || name.length() > 160 ||
                    !validCover(cover) || entries.size() >= MAX_FAVORITES) continue;
                entries.putIfAbsent(id, new Favorite(id, name, cover == null ? "" : cover));
            }
        } catch (IOException | RuntimeException error) {
            System.err.println("[xcloud-favorites] Could not load favorites: " + error.getMessage());
            entries.clear();
        }
    }

    synchronized boolean contains(String id) { return entries.containsKey(id); }
    synchronized Favorite get(String id) { return entries.get(id); }
    synchronized List<Favorite> all() { return List.copyOf(entries.values()); }

    synchronized boolean toggle(String id, String name, String cover) throws IOException {
        if (entries.containsKey(id)) {
            Favorite previous = entries.remove(id);
            try { save(); }
            catch (IOException error) { entries.put(id, previous); throw error; }
            try { Files.deleteIfExists(coverFile(id)); } catch (IOException ignored) { }
            return false;
        }
        if (!validId(id) || name == null || name.isBlank() || name.length() > 160 || !validCover(cover))
            throw new IOException("Invalid Xbox favorite");
        if (entries.size() >= MAX_FAVORITES) throw new IOException("Limit of 64 favorites reached");
        entries.put(id, new Favorite(id, name, cover == null ? "" : cover));
        try { save(); }
        catch (IOException error) { entries.remove(id); throw error; }
        return true;
    }

    synchronized void update(String id, String name, String cover) {
        Favorite current = entries.get(id);
        if (current == null || name == null || name.isBlank() || name.length() > 160 || !validCover(cover))
            return;
        String url = cover == null ? "" : cover;
        if (current.name.equals(name) && current.cover.equals(url)) return;
        entries.put(id, new Favorite(id, name, url));
        try { save(); } catch (IOException error) {
            entries.put(id, current);
            System.err.println("[xcloud-favorites] Could not refresh favorite: " + error.getMessage());
        }
    }

    private static String quote(String s) {
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 32) result.append(String.format("\\u%04x", (int)c));
                    else result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }

    private synchronized void save() throws IOException {
        Files.createDirectories(directory);
        List<String> records = new ArrayList<>();
        for (Favorite f : entries.values())
            records.add("{\"id\":" + quote(f.id()) + ",\"name\":" + quote(f.name()) +
                        ",\"cover\":" + quote(f.cover()) + "}");
        Path temp = Files.createTempFile(directory, ".favorites-", ".tmp");
        try {
            Files.writeString(temp, "[" + String.join(",", records) + "]\n", StandardCharsets.UTF_8);
            try { Files.move(temp, dataFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException error) {
                Files.move(temp, dataFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temp); }
    }

    private Path coverFile(String id) {
        if (!validId(id)) throw new IllegalArgumentException("Invalid favorite ID");
        return covers.resolve(id + ".png");
    }

    /** Never read arbitrarily large or malformed cached files onto the paint thread. */
    BufferedImage cachedCover(String id) {
        if (!contains(id)) return null;
        Path file;
        try { file = coverFile(id); }
        catch (IllegalArgumentException error) { return null; }
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_COVER_BYTES) return null;
            try (ImageInputStream stream = ImageIO.createImageInputStream(file.toFile())) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) return null;
                ImageReader reader = readers.next();
                try {
                    reader.setInput(stream);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || width > 1024 || height > 1024) return null;
                    return reader.read(0);
                } finally { reader.dispose(); }
            }
        } catch (IOException | RuntimeException error) { return null; }
    }

    /** Called off the UI thread; recheck membership after a pending download. */
    synchronized void cacheCover(String id, BufferedImage image) {
        if (!entries.containsKey(id) || image == null) return;
        try {
            Files.createDirectories(covers);
            Path temp = Files.createTempFile(covers, ".cover-", ".tmp");
            try {
                if (!ImageIO.write(image, "png", temp.toFile()) || Files.size(temp) > MAX_COVER_BYTES) return;
                if (!entries.containsKey(id)) return;
                try { Files.move(temp, coverFile(id), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException error) {
                    Files.move(temp, coverFile(id), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally { Files.deleteIfExists(temp); }
        } catch (IOException error) {
            System.err.println("[xcloud-favorites] Could not cache cover: " + error.getMessage());
        }
    }
}
