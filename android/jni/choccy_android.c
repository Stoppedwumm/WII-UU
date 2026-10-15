/*
 * Choccy Milk on Android: the game runs on a thread of its own (with a big stack, like the
 * desktop's: some cores go deep), draws into the game screen's window (ANativeWindow), and plays
 * its sound with AAudio, whose blocking writes keep the game at its real speed. Java
 * (wiiuu.android.Choccy) starts and stops it and passes the controls in.
 *
 * The game screen runs in its own process (":game"), so a crashing core only ends the game.
 */
#include "choccy_core.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define TAG "ChoccyMilk"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static pthread_t thread;
static atomic_int running, paused, sharp = 1;
static char core_path[1024], rom_path[1024], save_dir[1024], system_dir[1024];
static char *options;

/* ---- the window: set by Java, used by the game thread ---- */
static pthread_mutex_t window_lock = PTHREAD_MUTEX_INITIALIZER;
static ANativeWindow *window;
static int window_w, window_h, buffer_w, buffer_h;

/* whole-pixel enlargement before Android scales the rest of the way: sharp pixels stay sharp */
static int prescale(int w, int h) {
    if (!atomic_load(&sharp) || window_w <= 0 || window_h <= 0) return 1;
    int s = window_h / h < window_w / w ? window_h / h : window_w / w;
    return s < 1 ? 1 : s > 4 ? 4 : s;
}

static void on_video(void *user, const uint32_t *px, int w, int h) {
    (void) user;
    pthread_mutex_lock(&window_lock);
    if (window) {
        int s = prescale(w, h), bw = w * s, bh = h * s;
        if (bw != buffer_w || bh != buffer_h) {
            ANativeWindow_setBuffersGeometry(window, bw, bh, WINDOW_FORMAT_RGBX_8888);
            buffer_w = bw;
            buffer_h = bh;
        }
        ANativeWindow_Buffer buf;
        if (ANativeWindow_lock(window, &buf, NULL) == 0) {
            if (buf.width >= bw && buf.height >= bh) {
                uint32_t *dst = buf.bits;
                for (int y = 0; y < h; y++) {
                    const uint32_t *row = px + (size_t) y * w;
                    uint32_t *out = dst + (size_t) y * s * buf.stride;
                    for (int x = 0; x < w; x++) {
                        uint32_t p = row[x];         /* 0x00RRGGBB -> R, G, B, X in memory */
                        uint32_t c = 0xFF000000u | (p & 0xFF) << 16 | (p & 0xFF00) | (p >> 16 & 0xFF);
                        for (int k = 0; k < s; k++) out[x * s + k] = c;
                    }
                    for (int k = 1; k < s; k++) memcpy(out + (size_t) k * buf.stride, out, (size_t) bw * 4);
                }
            }
            ANativeWindow_unlockAndPost(window);
        }
    }
    pthread_mutex_unlock(&window_lock);
}

/* ---- sound ---- */
static AAudioStream *stream;
static atomic_int sound_ok;

static void open_sound(double rate) {
    AAudioStreamBuilder *b;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return;
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(b, 2);
    AAudioStreamBuilder_setSampleRate(b, (int32_t) (rate + 0.5));      /* Android converts to the speaker's rate */
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_SHARED);
    if (AAudioStreamBuilder_openStream(b, &stream) == AAUDIO_OK) {
        int32_t burst = AAudioStream_getFramesPerBurst(stream);
        int32_t want = (int32_t) (rate * 0.06);                          /* about 60 ms queued at most */
        if (burst > 0 && want < burst * 2) want = burst * 2;
        AAudioStream_setBufferSizeInFrames(stream, want);
        if (AAudioStream_requestStart(stream) == AAUDIO_OK) atomic_store(&sound_ok, 1);
        LOG("sound: %d Hz, %d frames queued", AAudioStream_getSampleRate(stream), AAudioStream_getBufferSizeInFrames(stream));
    } else {
        stream = NULL;
        LOG("no sound (AAudio didn't open)");
    }
    AAudioStreamBuilder_delete(b);
}

static double sound_rate;
static double sound_retry_at;
static double now(void);

static void close_sound(void) {
    if (!stream) return;
    AAudioStream_requestStop(stream);
    AAudioStream_close(stream);
    stream = NULL;
    atomic_store(&sound_ok, 0);
}

static void on_audio(void *user, const int16_t *frames, size_t n) {
    (void) user;
    if (!atomic_load(&sound_ok) || !stream) {
        /* the sound went (headphones, Bluetooth): try again now and then; the clock keeps time meanwhile */
        if (sound_rate > 0 && now() > sound_retry_at) {
            sound_retry_at = now() + 1.0;
            close_sound();
            open_sound(sound_rate);
        }
        return;
    }
    /* blocks while the queue is full: this is what keeps the game at its real speed */
    aaudio_result_t r = AAudioStream_write(stream, frames, (int32_t) n, 100 * 1000000LL);
    if (r < 0) {
        LOG("sound stopped: %s", AAudio_convertResultToText(r));
        atomic_store(&sound_ok, 0);
        sound_retry_at = now() + 0.2;
    }
}

/* ---- messages for Java (polled) ---- */
#define NOTES 16
static pthread_mutex_t notes_lock = PTHREAD_MUTEX_INITIALIZER;
static char notes[NOTES][160];
static int notes_head, notes_count;
static char ending[512];

static void note(const char *text) {
    pthread_mutex_lock(&notes_lock);
    int i = (notes_head + notes_count) % NOTES;
    snprintf(notes[i], sizeof notes[i], "%s", text);
    if (notes_count < NOTES) notes_count++;
    else notes_head = (notes_head + 1) % NOTES;
    pthread_mutex_unlock(&notes_lock);
}

static void on_log(void *user, const char *line) {
    (void) user;
    LOG("%s", line);
}

static void on_notice(void *user, const char *text) {
    (void) user;
    note(text);
}

/* ---- the game thread ---- */
static atomic_int want_state = -1;            /* slot * 2 + (1: save, 0: load) */

static double now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (double) t.tv_sec + t.tv_nsec / 1e9;
}

static void *game(void *arg) {
    (void) arg;
    cm_host host = {on_video, on_audio, on_log, on_notice, NULL};
    char err[512];
    if (cm_load(core_path, rom_path, save_dir, system_dir, options, &host, err, sizeof err) != 0) {
        LOG("didn't start: %s", err);
        snprintf(ending, sizeof ending, "%s", err);
        atomic_store(&running, 0);
        return NULL;
    }
    cm_av av = cm_get_av();
    sound_rate = av.sample_rate;
    open_sound(av.sample_rate);
    double next = now(), last_save = now();
    int was_paused = 0;
    while (atomic_load(&running)) {
        int st = atomic_exchange(&want_state, -1);
        if (st >= 0) cm_state(st & 1, st >> 1);
        int p = atomic_load(&paused);
        if (p != was_paused && stream) {              /* the sound pauses here, never while it's written */
            if (p) AAudioStream_requestPause(stream);
            else AAudioStream_requestStart(stream);
        }
        was_paused = p;
        if (p) {
            usleep(20000);
            next = now();
            continue;
        }
        cm_run();
        double t = now();
        if (!atomic_load(&sound_ok)) {               /* no sound: keep time by the clock */
            next += 1.0 / cm_get_av().fps;
            if (next > t) usleep((useconds_t) ((next - t) * 1e6));
            else if (t - next > 0.1) next = t;
        }
        if (t - last_save > 1.0) {                   /* the game's own save: soon after it changes */
            cm_save_sram();
            last_save = t;
        }
    }
    close_sound();
    cm_unload();
    LOG("stopped");
    return NULL;
}

/* ---- Java: wiiuu.android.Choccy ---- */
static void copy(JNIEnv *env, jstring s, char *out, size_t len) {
    const char *c = (*env)->GetStringUTFChars(env, s, NULL);
    snprintf(out, len, "%s", c ? c : "");
    if (c) (*env)->ReleaseStringUTFChars(env, s, c);
}

JNIEXPORT jboolean JNICALL Java_wiiuu_android_Choccy_start(JNIEnv *env, jclass cls, jstring core, jstring rom, jstring saves,
                                                          jstring system, jstring opts) {
    (void) cls;
    if (atomic_load(&running)) return JNI_FALSE;
    copy(env, core, core_path, sizeof core_path);
    copy(env, rom, rom_path, sizeof rom_path);
    copy(env, saves, save_dir, sizeof save_dir);
    copy(env, system, system_dir, sizeof system_dir);
    const char *o = (*env)->GetStringUTFChars(env, opts, NULL);
    free(options);
    options = strdup(o ? o : "");
    if (o) (*env)->ReleaseStringUTFChars(env, opts, o);
    ending[0] = 0;
    atomic_store(&running, 1);
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setstacksize(&attr, 16 * 1024 * 1024);
    int r = pthread_create(&thread, &attr, game, NULL);
    pthread_attr_destroy(&attr);
    if (r != 0) {
        atomic_store(&running, 0);
        snprintf(ending, sizeof ending, "couldn't start the game's thread");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_wiiuu_android_Choccy_surface(JNIEnv *env, jclass cls, jobject surface, jint w, jint h) {
    (void) cls;
    pthread_mutex_lock(&window_lock);
    if (window) ANativeWindow_release(window);
    window = surface ? ANativeWindow_fromSurface(env, surface) : NULL;
    window_w = w;
    window_h = h;
    buffer_w = buffer_h = 0;                          /* set the size again on the next frame */
    pthread_mutex_unlock(&window_lock);
}

JNIEXPORT void JNICALL Java_wiiuu_android_Choccy_input(JNIEnv *env, jclass cls, jint port, jint buttons, jint lx, jint ly,
                                                      jint rx, jint ry) {
    (void) env;
    (void) cls;
    cm_input(port, (uint32_t) buttons, (int16_t) lx, (int16_t) ly, (int16_t) rx, (int16_t) ry);
}

JNIEXPORT void JNICALL Java_wiiuu_android_Choccy_pause(JNIEnv *env, jclass cls, jboolean p) {
    (void) env;
    (void) cls;
    atomic_store(&paused, p ? 1 : 0);
}

JNIEXPORT void JNICALL Java_wiiuu_android_Choccy_sharp(JNIEnv *env, jclass cls, jboolean s) {
    (void) env;
    (void) cls;
    atomic_store(&sharp, s ? 1 : 0);
    pthread_mutex_lock(&window_lock);
    buffer_w = buffer_h = 0;
    pthread_mutex_unlock(&window_lock);
}

JNIEXPORT void JNICALL Java_wiiuu_android_Choccy_state(JNIEnv *env, jclass cls, jboolean save, jint slot) {
    (void) env;
    (void) cls;
    if (slot >= 0 && slot <= 9) atomic_store(&want_state, slot * 2 + (save ? 1 : 0));
}

JNIEXPORT void JNICALL Java_wiiuu_android_Choccy_quit(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    if (!thread) return;
    atomic_store(&paused, 0);
    atomic_store(&running, 0);
    pthread_join(thread, NULL);
    thread = 0;
}

JNIEXPORT jboolean JNICALL Java_wiiuu_android_Choccy_running(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return atomic_load(&running) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_wiiuu_android_Choccy_ending(JNIEnv *env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, ending);
}

JNIEXPORT jstring JNICALL Java_wiiuu_android_Choccy_nextNotice(JNIEnv *env, jclass cls) {
    (void) cls;
    char text[160];
    int got = 0;
    pthread_mutex_lock(&notes_lock);
    if (notes_count > 0) {
        snprintf(text, sizeof text, "%s", notes[notes_head]);
        notes_head = (notes_head + 1) % NOTES;
        notes_count--;
        got = 1;
    }
    pthread_mutex_unlock(&notes_lock);
    return got ? (*env)->NewStringUTF(env, text) : NULL;
}

JNIEXPORT jfloat JNICALL Java_wiiuu_android_Choccy_aspect(JNIEnv *env, jclass cls) {
    (void) env;
    (void) cls;
    return cm_get_av().aspect;
}

JNIEXPORT jstring JNICALL Java_wiiuu_android_Choccy_coreName(JNIEnv *env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, cm_core_name());
}
