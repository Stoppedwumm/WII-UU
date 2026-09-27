package wiiuu.core;

import java.nio.file.Path;
import java.util.List;

/**
 * A launchable game found on disk.
 *
 * @param system the console it belongs to
 * @param name   cleaned-up display name
 * @param path   file handed to the emulator
 * @param covers candidate cover / icon images, best first (may not exist)
 */
public record Game(GameSystem system, String name, Path path, List<Path> covers) {

    /** Stable short id used by the phone gamepad to reference a game. */
    public String id() {
        return Integer.toHexString(path.toAbsolutePath().toString().hashCode());
    }
}
