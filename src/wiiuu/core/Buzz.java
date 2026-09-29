package wiiuu.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import wiiuu.input.PadButton;

/**
 * Buzz! mode: for the PS2 Buzz! quiz games, every phone becomes a Buzz! buzzer (a big red button and
 * four coloured answer buttons) and PCSX2 gets its emulated Buzz! controllers, four buzzers per USB
 * port: players 1-4 on port 1 and, in 8-player mode, 5-8 on port 2.
 *
 * <p>It switches on by itself for PS2 games whose name matches {@code buzz.games} (default: the
 * word "Buzz"); {@code buzz.enabled=false} turns it off.
 */
public final class Buzz {
    /** PCSX2's name for its Buzz! controller device, and the colours of its buttons. */
    static final String PCSX2_DEVICE = "buzz_device";
    static final Map<PadButton, String> COLORS = new LinkedHashMap<>();

    static {
        COLORS.put(PadButton.BUZZ_RED, "Red");
        COLORS.put(PadButton.BUZZ_BLUE, "Blue");
        COLORS.put(PadButton.BUZZ_ORANGE, "Orange");
        COLORS.put(PadButton.BUZZ_GREEN, "Green");
        COLORS.put(PadButton.BUZZ_YELLOW, "Yellow");
    }

    private Buzz() {}

    /** Whether this game is played with buzzers. */
    public static boolean active(Config config, Game game) {
        if (game == null || !config.getBool("buzz.enabled", true) || !game.system().id().equals("ps2")) return false;
        try {
            return Pattern.compile(config.get("buzz.games", "(?i)\\bbuzz")).matcher(game.name()).find();
        } catch (RuntimeException e) {
            return game.name().toLowerCase(Locale.ROOT).contains("buzz");
        }
    }

    /** How many phones may play at once (4, or 8 in 8-player mode). */
    public static int players(Config config) {
        return config.getInt("server.maxPlayers", 4) > 4 ? 8 : 4;
    }
}
