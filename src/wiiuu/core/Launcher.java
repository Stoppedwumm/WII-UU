package wiiuu.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Starts one emulator process at a time and reports when it ends. */
public final class Launcher {

    public interface Listener {
        void started(Game game);

        /** @param quickFailure true when the emulator died within a few seconds with an error code */
        void exited(Game game, int exitCode, boolean quickFailure, Path log);
    }

    public static final class LaunchException extends Exception {
        public LaunchException(String message) {
            super(message);
        }
    }

    private final Config config;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private Process process;
    private Game current;

    public Launcher(Config config) {
        this.config = config;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    public synchronized Game current() {
        return isRunning() ? current : null;
    }

    public synchronized void launch(Game game) throws LaunchException {
        if (isRunning()) throw new LaunchException(current.name() + " is already running");
        String template = config.command(game.system());
        List<String> cmd = expand(template, game);
        if (cmd.isEmpty()) throw new LaunchException("No emulator command set for " + game.system().name());

        Path log = config.logDir().resolve(game.system().id() + ".log");
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Path dir = game.path().getParent();
        if (dir != null) pb.directory(dir.toFile());
        pb.redirectErrorStream(true);
        try {
            Files.createDirectories(log.getParent());
            pb.redirectOutput(log.toFile());
        } catch (IOException e) {
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        }
        final Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new LaunchException("Could not start " + game.system().emulator() + " (\"" + cmd.get(0)
                    + "\"). Install it or set the command in Settings.");
        }
        process = p;
        current = game;
        long startedAt = System.currentTimeMillis();
        for (Listener l : listeners) l.started(game);
        p.onExit().thenAccept(done -> {
            synchronized (Launcher.this) {
                if (process == done) {
                    process = null;
                    current = null;
                }
            }
            int code = done.exitValue();
            boolean quick = code != 0 && System.currentTimeMillis() - startedAt < 4000;
            for (Listener l : listeners) l.exited(game, code, quick, log);
        });
    }

    /** Asks the emulator to quit, then force-kills it if it refuses. */
    public void stop() {
        Process p;
        synchronized (this) {
            p = process;
        }
        if (p == null || !p.isAlive()) return;
        p.descendants().forEach(ProcessHandle::destroy);
        p.destroy();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (p.isAlive()) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        }, "emulator-kill");
        t.setDaemon(true);
        t.start();
    }

    /** Splits a command template into arguments (honouring quotes) and fills in placeholders. */
    static List<String> expand(String template, Game game) {
        List<String> out = new ArrayList<>();
        for (String tok : tokenize(template)) {
            out.add(tok.replace("{rom}", game.path().toAbsolutePath().toString())
                    .replace("{dir}", String.valueOf(game.path().toAbsolutePath().getParent()))
                    .replace("{name}", game.name()));
        }
        return out;
    }

    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                else cur.append(c);
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (inToken) out.add(cur.toString());
        return out;
    }
}
