package wiiuu.core;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RetroAchievements while Choccy Milk plays a game (in WII-UU and in the Choccy Milk app): finds
 * the game's achievements, hands the ones still locked to the player, and unlocks them on
 * RetroAchievements as the player reports them.
 */
public final class ChoccyCheevos {
    private ChoccyCheevos() {}

    /** What to show. */
    public interface Events {
        /** The game has achievements: how many, how many unlocked already (or, with a warning, why none can be earned). */
        void found(RaConnect.GameSet set);

        /** One unlocked now (error: RetroAchievements didn't take it, null when it did). */
        void unlocked(RaConnect.Achievement a, int have, int total, String error);
    }

    /** Consoles whose games RetroAchievements knows by a ROM hash that WII-UU can make. */
    private static final Set<String> SUPPORTED = Set.of("nes", "snes", "gb", "gba", "n64", "nds", "sms", "genesis");

    public static boolean supports(Game g) {
        return SUPPORTED.contains(g.system().id());
    }

    /** Starts achievements for a game that has just started in Choccy Milk (in the background). */
    public static void start(RaConnect ra, Choccy.Session session, Game game, Events events) {
        if (ra == null || !ra.enabled() || !supports(game)) return;
        Thread t = new Thread(() -> {
            try {
                RaConnect.GameSet set = ra.load(game);
                if (set == null) {
                    System.out.println("[achievements] " + game.name() + " isn't on RetroAchievements");
                    return;
                }
                if (set.warning() != null) {
                    System.out.println("[achievements] " + set.warning());
                    events.found(set);
                    return;
                }
                System.out.println("[achievements] " + set.title() + ": " + set.unlocked().size() + " of "
                        + set.achievements().size() + " unlocked (softcore)");
                Map<Integer, RaConnect.Achievement> byId = new ConcurrentHashMap<>();
                for (RaConnect.Achievement a : set.locked()) byId.put(a.id(), a);
                Set<Integer> have = ConcurrentHashMap.newKeySet();
                have.addAll(set.unlocked());
                session.onAchievement(id -> {
                    RaConnect.Achievement a = byId.remove(id);
                    if (a == null) return;
                    have.add(id);
                    Thread award = new Thread(() -> {
                        String err = ra.award(id, set.hash());
                        System.out.println("[achievements] unlocked " + a.title() + (err == null ? "" : " (not sent: " + err + ")"));
                        events.unlocked(a, have.size(), set.achievements().size(), err);
                    }, "ra-award");
                    award.setDaemon(true);
                    award.start();
                });
                session.achievements(set.console(), set.locked());
                events.found(set);
            } catch (Exception e) {
                System.err.println("[achievements] " + game.name() + ": " + e.getMessage());
            }
        }, "ra-load");
        t.setDaemon(true);
        t.start();
    }
}
