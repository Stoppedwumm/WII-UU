/* Runs a game for a while with choccy_core.c on Linux (a desktop core), to test the host without a
 * phone: frames, sound, a button press, save states, the game's save. Prints what it sees. */
#include "../jni/choccy_core.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static long frames, sound_frames, notices;
static unsigned long long checksum;
static int last_w, last_h;

static void on_video(void *u, const uint32_t *px, int w, int h) {
    (void) u;
    frames++;
    last_w = w;
    last_h = h;
    unsigned long long s = 0;
    for (int i = 0; i < w * h; i += 7) s = s * 31 + px[i];
    checksum = s;
}
static void on_audio(void *u, const int16_t *f, size_t n) { (void) u; (void) f; sound_frames += (long) n; }
static void on_log(void *u, const char *line) { (void) u; printf("  log: %s\n", line); }
static void on_notice(void *u, const char *t) { (void) u; notices++; printf("  notice: %s\n", t); }

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: host_test CORE ROM SAVEDIR [options]\n");
        return 2;
    }
    cm_host h = {on_video, on_audio, on_log, on_notice, NULL};
    char err[512];
    if (cm_load(argv[1], argv[2], argv[3], argv[3], argc > 4 ? argv[4] : "", &h, err, sizeof err) != 0) {
        printf("LOAD FAILED: %s\n", err);
        return 1;
    }
    cm_av av = cm_get_av();
    printf("core %s, %dx%d aspect %.3f, %.2f fps, %.0f Hz\n", cm_core_name(), av.width, av.height, av.aspect, av.fps, av.sample_rate);
    for (int i = 0; i < 120; i++) cm_run();
    unsigned long long before = checksum;
    for (int i = 0; i < 30; i++) {
        cm_input(0, i < 2 ? 1u << 5 : 0, 0, 0, 0, 0);       /* Down, for two frames */
        cm_run();
    }
    printf("after %ld frames: %dx%d, %ld sound frames (%.2f s); Down changed the picture: %s\n", frames, last_w, last_h,
           sound_frames, sound_frames / av.sample_rate, checksum != before ? "yes" : "NO");
    unsigned long long at = checksum;
    printf("save state: %d\n", cm_state(1, 3));
    for (int i = 0; i < 30; i++) {
        cm_input(0, i < 2 ? 1u << 5 : 0, 0, 0, 0, 0);
        cm_run();
    }
    unsigned long long later = checksum;
    printf("load state: %d\n", cm_state(0, 3));
    cm_run();
    printf("moved on: %s; after loading the picture is the saved one again: %s\n", later != at ? "yes" : "no",
           checksum == at ? "yes" : "NO");
    printf("missing state: %d\n", cm_state(0, 9));
    printf("game save: %d\n", cm_save_sram());
    cm_unload();
    printf("done, %ld notices\n", notices);
    return 0;
}
