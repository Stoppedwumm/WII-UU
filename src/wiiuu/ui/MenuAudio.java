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

    /** Hands over the rendered background loop (interleaved stereo at {@link #RATE}). */
    void setMusic(short[] loop) {
        synchronized (lock) {
            music = loop;
            musicPos = 0;
            wake();
        }
    }

    boolean hasMusic() {
        synchronized (lock) {
            return music != null;
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
        return !voices.isEmpty() || (music != null && (musicGain > 0.0005f || musicTarget > 0));
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
        int frames = music.length / 2;
        for (int i = 0; i < BLOCK; i++) {
            // ~1.5 s fade in, ~0.6 s fade out
            if (musicGain < musicTarget) musicGain = Math.min(musicTarget, musicGain + 1f / (RATE * 1.5f));
            else if (musicGain > musicTarget) musicGain = Math.max(musicTarget, musicGain - 1f / (RATE * 0.6f));
            if (musicGain <= 0f) continue;
            float g = musicGain * musicGain * musicVolume / 32768f;
            mix[i * 2] += music[musicPos * 2] * g;
            mix[i * 2 + 1] += music[musicPos * 2 + 1] * g;
            if (++musicPos >= frames) musicPos = 0;
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
