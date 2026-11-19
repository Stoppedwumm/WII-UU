package wiiuu.ui;

/** WII-UU-specific appearance and dialogue for the shared animated mascot. */
final class Mascot extends AnimatedMascot {
    Mascot() {
        super(() -> new Style("WII-", "UU", MenuView.ACCENT, MenuView.dark, MenuView::font),
                System::currentTimeMillis);
    }

    // ---- what it says -----------------------------------------------------------------------------

    /** Hello, by the time of day. */
    static String greeting() {
        int hour = java.time.LocalTime.now().getHour();
        if (hour < 5) return "Up late, huh? Me too. I don't sleep. I'm a console.";
        if (hour < 11) return "Good morning! Ready to play something?";
        if (hour < 17) return "Good afternoon! What are we playing?";
        if (hour < 22) return "Good evening! Perfect time for a game.";
        return "Getting late... one more game?";
    }

    /** When you click it. */
    static final String[] POKED = {
            "Hi! I'm WII-UU. Well, the little one.",
            "That tickles.",
            "Press F1 for Settings. Or don't. I'm not your boss.",
            "Did you know? I make the menu music myself.",
            "Psst. Leave me alone for three minutes and I'll play by myself.",
            "Boop.",
            "I'm not a button. I'm a friend.",
            "Your phone can be a GamePad. Just saying.",
    };
}
