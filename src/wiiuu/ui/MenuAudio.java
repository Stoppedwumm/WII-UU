package wiiuu.ui;

import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * The menu's one audio output: background music and sound effects mixed into a single line.
 *
 * <p>One line instead of one per blip keeps effects in time with the music and works on sound
 * cards that can't mix. The line is closed whenever nothing plays (for example during a game),
 * so emulators always get the audio device. Everything fails silently without audio.
 */
final class MenuAudio {
    static final int RATE = 44100;
    private static final int BLOCK = 441;                 // 10 ms per mix step
    private static final long IDLE_CLOSE_MS = 1500;

    private static final MenuAudio INSTANCE = new MenuAudio();

    static MenuAudio get() {
        return INSTANCE;
    }

    private final Object lock = new Object();
    // guarded by lock
    private final List<Voice> voices = new ArrayList<>();
    private short[] music;                // interleaved stereo loop, once rendered
    private int musicPos;                 // frame
    private float musicGain;              // current, ramps toward target
    private float musicTarget;
    private float musicVolume = 0.5f;
    private short[] pending;              // fades the music out, then starts this loop
    private int wraps;                    // times the current loop has played through
    private java.util.function.IntConsumer onWrap;
    private short[] queued;               // starts right where the loop ends, without a fade
    private Runnable onQueuedStart;
    private final float[] scope = new float[4096];   // the music just played (mono), for the visualizer
    private int scopePos;
    private boolean broken;
    private Thread thread;

    private record Voice(short[] pcm, float gain, int[] pos) {}

    private MenuAudio() {}

    /** Plays a mono effect once. */
    void play(short[] mono, float gain) {
        synchronized (lock) {
            if (broken) return;
            voices.add(new Voice(mono, gain, new int[1]));
            wake();
        }
    }

    /**
     * Hands over a background loop (interleaved stereo at {@link #RATE}). Music already playing
     * fades out first, then the new loop starts from its beginning.
     */
    void setMusic(short[] loop) {
        synchronized (lock) {
            if (music == null || musicGain <= 0f) {
                music = loop;
                musicPos = 0;
                pending = null;
            } else {
                pending = loop;
            }
            wraps = 0;
            queued = null;
            onQueuedStart = null;
            wake();
        }
    }

    /**
     * Plays {@code next} straight after the current music reaches its end, without a gap or fade
     * (the Extended Mix, which arrives a piece at a time). {@code onStart} runs on the audio thread,
     * under the mixer's lock, when it begins.
     */
    void queue(short[] next, Runnable onStart) {
        synchronized (lock) {
            queued = next;
            onQueuedStart = onStart;
        }
    }

    /** The last {@code dst.length} music samples played (mono, after volume and fades); silence without sound. */
    void scope(float[] dst) {
        synchronized (lock) {
            if (broken || music == null || musicGain <= 0f) {
                java.util.Arrays.fill(dst, 0f);
                return;
            }
            int n = Math.min(dst.length, scope.length);
            for (int i = 0; i < n; i++) dst[i] = scope[(scopePos - n + i + scope.length) % scope.length];
        }
    }

    /** Whether music can be heard right now. */
    boolean musicAudible() {
        synchronized (lock) {
            return music != null && musicGain > 0.01f && pending == null;
        }
    }

    /** Called on the audio thread, under the mixer's lock, each time the loop comes round. */
    void onLoopEnd(java.util.function.IntConsumer timesPlayed) {
        synchronized (lock) {
            onWrap = timesPlayed;
        }
    }

    /** Fades the music in (true) or out (false); it resumes where it paused. */
    void musicOn(boolean on) {
        synchronized (lock) {
            musicTarget = on ? 1f : 0f;
            wake();
        }
    }

    /** 0..1 */
    void setMusicVolume(float v) {
        synchronized (lock) {
            musicVolume = Math.max(0f, Math.min(1f, v));
        }
    }

    private void wake() {
        if (thread == null || !thread.isAlive()) {
            thread = new Thread(this::run, "menu-audio");
            thread.setDaemon(true);
            thread.start();
        }
        lock.notifyAll();
    }

    private boolean busy() {
        return !voices.isEmpty() || (music != null && (musicGain > 0.0005f || musicTarget > 0)) || pending != null;
    }

    private void run() {
        AudioFormat fmt = new AudioFormat(RATE, 16, 2, true, false);
        SourceDataLine line = null;
        byte[] out = new byte[BLOCK * 4];
        float[] mix = new float[BLOCK * 2];
        long idleSince = 0;
        try {
            while (true) {
                synchronized (lock) {
                    if (!busy()) {
                        if (idleSince == 0) idleSince = System.currentTimeMillis();
                        if (line == null || System.currentTimeMillis() - idleSince > IDLE_CLOSE_MS) {
                            if (line != null) {
                                line.drain();
                                line.close();
                                line = null;
                            }
                            lock.wait(1000);
                            continue;
                        }
                    } else {
                        idleSince = 0;
                    }
                    java.util.Arrays.fill(mix, 0f);
                    mixMusic(mix);
                    mixVoices(mix);
                }
                if (line == null) {
                    line = AudioSystem.getSourceDataLine(fmt);
                    line.open(fmt, RATE / 20 * 4);              // 50 ms: effects stay snappy
                    line.start();
                }
                for (int i = 0; i < mix.length; i++) {
                    float s = mix[i];
                    s = s > 1f ? 1f : Math.max(-1f, s);
                    short v = (short) (s * 32767);
                    out[i * 2] = (byte) v;
                    out[i * 2 + 1] = (byte) (v >> 8);
                }
                line.write(out, 0, out.length);                   // blocks: this paces the loop
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception | LinkageError e) {
            synchronized (lock) {
                broken = true;                                    // no audio device: stay quiet
                voices.clear();
            }
        } finally {
            if (line != null) line.close();
        }
    }

    private void mixMusic(float[] mix) {
        if (music == null) return;
        for (int i = 0; i < BLOCK; i++) {
            // ~1.5 s fade in, ~0.6 s fade out
            float target = pending != null ? 0f : musicTarget;
            if (musicGain < target) musicGain = Math.min(target, musicGain + 1f / (RATE * 1.5f));
            else if (musicGain > target) musicGain = Math.max(target, musicGain - 1f / (RATE * 0.6f));
            if (musicGain <= 0f) {
                if (pending != null) {
                    music = pending;
                    pending = null;
                    musicPos = 0;
                }
                scope[scopePos] = 0;
                scopePos = (scopePos + 1) % scope.length;
                continue;
            }
            float g = musicGain * musicGain * musicVolume / 32768f;
            float sl = music[musicPos * 2] * g, sr = music[musicPos * 2 + 1] * g;
            mix[i * 2] += sl;
            mix[i * 2 + 1] += sr;
            scope[scopePos] = (sl + sr) * 0.5f;
            scopePos = (scopePos + 1) % scope.length;
            if (++musicPos >= music.length / 2) {
                musicPos = 0;
                if (queued != null && pending == null) {
                    music = queued;
                    queued = null;
                    wraps = 0;
                    Runnable start = onQueuedStart;
                    onQueuedStart = null;
                    if (start != null) start.run();
                    continue;
                }
                wraps++;
                if (onWrap != null) onWrap.accept(wraps);
            }
        }
    }

    private void mixVoices(float[] mix) {
        for (int v = voices.size() - 1; v >= 0; v--) {
            Voice voice = voices.get(v);
            int p = voice.pos[0];
            float g = voice.gain / 32768f;
            int n = Math.min(BLOCK, voice.pcm.length - p);
            for (int i = 0; i < n; i++) {
                float s = voice.pcm[p + i] * g;
                mix[i * 2] += s;
                mix[i * 2 + 1] += s;
            }
            voice.pos[0] = p + n;
            if (voice.pos[0] >= voice.pcm.length) voices.remove(v);
        }
    }
}
