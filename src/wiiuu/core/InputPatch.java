package wiiuu.core;

import java.util.List;

/**
 * A temporary controller mapping for one emulator: for as long as a game runs, the emulator's
 * own controller settings are set to exactly the keys WII-UU types, then put back.
 */
interface InputPatch {
    /** Whether this patch is for the emulator in {@code cmd}. */
    boolean applies(Game game, List<String> cmd);

    /** Writes the mapping; returns the command to run (possibly with extra arguments). */
    List<String> before(Game game, List<String> cmd);

    /** Puts the user's own settings back. */
    void after();

    /** At start: undo a mapping left behind if WII-UU was killed while a game ran. */
    void recover();
}
