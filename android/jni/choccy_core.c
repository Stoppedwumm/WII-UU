/*
 * Choccy Milk's libretro host in C (see choccy_core.h). It answers cores the way the desktop
 * Choccy Milk does (engine/src-choccy/wiiuu/choccy/ChoccyMilk.java): software pictures only, core
 * options through the old SET_VARIABLES interface (first value as the default), input as bitmasks,
 * the game's save next to the save states.
 */
#define _GNU_SOURCE
#include "choccy_core.h"
#include "libretro_min.h"

#include <dlfcn.h>
#include <errno.h>
#include <stdarg.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* ---- the core's functions ---- */
static void *lib;
static void (*core_set_environment)(retro_environment_t);
static void (*core_set_video)(retro_video_refresh_t);
static void (*core_set_audio)(retro_audio_sample_t);
static void (*core_set_audio_batch)(retro_audio_sample_batch_t);
static void (*core_set_input_poll)(retro_input_poll_t);
static void (*core_set_input_state)(retro_input_state_t);
static void (*core_init)(void);
static void (*core_deinit)(void);
static void (*core_get_system_info)(struct retro_system_info *);
static void (*core_get_av_info)(struct retro_system_av_info *);
static bool (*core_load_game)(const struct retro_game_info *);
static void (*core_unload_game)(void);
static void (*core_run)(void);
static size_t (*core_serialize_size)(void);
static bool (*core_serialize)(void *, size_t);
static bool (*core_unserialize)(const void *, size_t);
static void *(*core_get_memory_data)(unsigned);
static size_t (*core_get_memory_size)(unsigned);

static cm_host host;
static cm_av av = {60, 44100, 256, 240, 4.0f / 3};
static int pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
static char save_dir[1024], system_dir[1024], base_name[512], core_name[256];
static void *rom_data;
static int inited, loaded;

static void say(const char *fmt, ...) {
    char line[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof line, fmt, ap);
    va_end(ap);
    if (host.log) host.log(host.user, line);
}

/* ---- core options: the core's defaults, the overrides given to cm_load ---- */
#define MAX_OPTIONS 512
static struct { char *key, *value; } defaults[MAX_OPTIONS], overrides[MAX_OPTIONS];
static int n_defaults, n_overrides;

static const char *option(const char *key) {
    for (int i = 0; i < n_overrides; i++) if (!strcmp(overrides[i].key, key)) return overrides[i].value;
    for (int i = 0; i < n_defaults; i++) if (!strcmp(defaults[i].key, key)) return defaults[i].value;
    return NULL;
}

static void parse_overrides(const char *text) {
    if (!text) return;
    const char *p = text;
    while (*p && n_overrides < MAX_OPTIONS) {
        const char *end = strchr(p, '\n');
        size_t len = end ? (size_t) (end - p) : strlen(p);
        const char *eq = memchr(p, '=', len);
        if (eq && eq > p) {
            overrides[n_overrides].key = strndup(p, (size_t) (eq - p));
            overrides[n_overrides].value = strndup(eq + 1, len - (size_t) (eq - p) - 1);
            n_overrides++;
        }
        if (!end) break;
        p = end + 1;
    }
}

/* "Description; first|second|third": the first is the default */
static void set_variables(const struct retro_variable *vars) {
    for (; vars && vars->key; vars++) {
        if (n_defaults >= MAX_OPTIONS) break;
        const char *desc = vars->value ? vars->value : "";
        const char *semi = strstr(desc, "; ");
        const char *first = semi ? semi + 2 : desc;
        const char *bar = strchr(first, '|');
        size_t len = bar ? (size_t) (bar - first) : strlen(first);
        int i = 0;
        while (i < n_defaults && strcmp(defaults[i].key, vars->key)) i++;
        if (i == n_defaults) {
            defaults[i].key = strdup(vars->key);
            n_defaults++;
        } else {
            free(defaults[i].value);
        }
        defaults[i].value = strndup(first, len);
    }
}

/* ---- the interfaces cores ask for ---- */
static void core_log(enum retro_log_level level, const char *fmt, ...) {
    if (level < RETRO_LOG_INFO) return;
    char line[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof line, fmt, ap);
    va_end(ap);
    size_t n = strlen(line);
    while (n > 0 && (line[n - 1] == '\n' || line[n - 1] == '\r')) line[--n] = 0;
    if (n) say("[core] %s", line);
}

static int64_t perf_usec(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (int64_t) t.tv_sec * 1000000 + t.tv_nsec / 1000;
}
static uint64_t perf_features(void) { return 0; }
static int64_t perf_counter(void) { return perf_usec(); }
static void perf_nothing_c(struct retro_perf_counter *c) { (void) c; }
static void perf_nothing(void) {}
static bool rumble(unsigned port, int effect, uint16_t strength) { (void) port; (void) effect; (void) strength; return false; }

static const struct retro_perf_callback perf = {perf_usec, perf_features, perf_counter, perf_nothing_c, perf_nothing_c,
                                                perf_nothing_c, perf_nothing};

static void read_geometry(const struct retro_game_geometry *g) {
    if (g->base_width > 0 && g->base_height > 0) {
        av.width = (int) g->base_width;
        av.height = (int) g->base_height;
    }
    av.aspect = g->aspect_ratio > 0 ? g->aspect_ratio : (float) av.width / (float) av.height;
}

static void read_av(const struct retro_system_av_info *info) {
    read_geometry(&info->geometry);
    if (info->timing.fps > 1 && info->timing.fps < 500) av.fps = info->timing.fps;
    if (info->timing.sample_rate > 1000) av.sample_rate = info->timing.sample_rate;
}

static bool environment(unsigned cmd, void *data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            ((struct retro_log_callback *) data)->log = core_log;
            return true;
        case RETRO_ENVIRONMENT_GET_PERF_INTERFACE:
            *(struct retro_perf_callback *) data = perf;
            return true;
        case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
            ((struct retro_rumble_interface *) data)->set_rumble_state = rumble;
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *(bool *) data = true;
            return true;
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            const struct retro_message *m = data;
            if (m && m->msg && host.notice) host.notice(host.user, m->msg);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
            const char *msg = data ? *(const char *const *) data : NULL;      /* its first field is the text */
            if (msg && host.notice) host.notice(host.user, msg);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *(const char **) data = system_dir;
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *(const char **) data = save_dir;
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            int f = *(const int *) data;
            if (f < 0 || f > 2) return false;
            pixel_format = f;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_HW_RENDER:                /* no OpenGL / Vulkan in Choccy Milk */
            say("[choccy] the core asks for a graphics chip context: not here");
            return false;
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            struct retro_variable *v = data;
            const char *value = v && v->key ? option(v->key) : NULL;
            if (!value) return false;
            v->value = value;
            return true;
        }
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            set_variables(data);
            return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *(bool *) data = false;
            return true;
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO:
            read_av(data);
            return true;
        case RETRO_ENVIRONMENT_SET_GEOMETRY:
            read_geometry(data);
            return true;
        case RETRO_ENVIRONMENT_GET_USERNAME:
            *(const char **) data = "WII-UU";
            return true;
        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            *(unsigned *) data = 0;                          /* English */
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:     /* 0: cores use SET_VARIABLES */
            *(unsigned *) data = 0;
            return true;
        case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION:
            *(unsigned *) data = 0;
            return true;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            if (data) *(int *) data = 3;
            return true;
        default:
            return false;
    }
}

/* ---- pictures ---- */
static uint32_t *frame;
static size_t frame_cap;

static void video(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || w == 0 || h == 0 || !host.video) return;       /* the same frame again */
    size_t n = (size_t) w * h;
    if (n > frame_cap) {
        uint32_t *bigger = realloc(frame, n * sizeof *frame);
        if (!bigger) return;
        frame = bigger;
        frame_cap = n;
    }
    for (unsigned y = 0; y < h; y++) {
        const uint8_t *row = (const uint8_t *) data + y * pitch;
        uint32_t *out = frame + (size_t) y * w;
        if (pixel_format == RETRO_PIXEL_FORMAT_XRGB8888) {
            const uint32_t *in = (const uint32_t *) row;
            for (unsigned x = 0; x < w; x++) out[x] = in[x] & 0xFFFFFF;
        } else if (pixel_format == RETRO_PIXEL_FORMAT_RGB565) {
            const uint16_t *in = (const uint16_t *) row;
            for (unsigned x = 0; x < w; x++) {
                uint32_t v = in[x], r = v >> 11 & 31, g = v >> 5 & 63, b = v & 31;
                out[x] = (r << 3 | r >> 2) << 16 | (g << 2 | g >> 4) << 8 | (b << 3 | b >> 2);
            }
        } else {
            const uint16_t *in = (const uint16_t *) row;
            for (unsigned x = 0; x < w; x++) {
                uint32_t v = in[x], r = v >> 10 & 31, g = v >> 5 & 31, b = v & 31;
                out[x] = (r << 3 | r >> 2) << 16 | (g << 3 | g >> 2) << 8 | (b << 3 | b >> 2);
            }
        }
    }
    host.video(host.user, frame, (int) w, (int) h);
}

/* ---- sound: collected over a frame, handed on after it ---- */
static int16_t *sound;
static size_t sound_frames, sound_cap;

static void sound_room(size_t more) {
    if (sound_frames + more <= sound_cap) return;
    size_t cap = sound_cap ? sound_cap : 4096;
    while (cap < sound_frames + more) cap *= 2;
    int16_t *bigger = realloc(sound, cap * 2 * sizeof *sound);
    if (!bigger) return;
    sound = bigger;
    sound_cap = cap;
}

static void audio_sample(int16_t l, int16_t r) {
    sound_room(1);
    if (sound_frames >= sound_cap) return;
    sound[sound_frames * 2] = l;
    sound[sound_frames * 2 + 1] = r;
    sound_frames++;
}

static size_t audio_batch(const int16_t *data, size_t frames) {
    sound_room(frames);
    if (sound_frames + frames > sound_cap) return frames;
    memcpy(sound + sound_frames * 2, data, frames * 2 * sizeof *data);
    sound_frames += frames;
    return frames;
}

/* ---- controls: written by any thread, read by the game's ---- */
#define PORTS 4
static _Atomic uint32_t held[PORTS], latched[PORTS];
static _Atomic int32_t sticks[PORTS];        /* left x, y as two 16-bit halves */
static _Atomic int32_t rsticks[PORTS];
static uint32_t seen[PORTS];

void cm_input(int port, uint32_t buttons, int16_t lx, int16_t ly, int16_t rx, int16_t ry) {
    if (port < 0 || port >= PORTS) return;
    atomic_store(&held[port], buttons);
    atomic_fetch_or(&latched[port], buttons);             /* a tap between two looks still counts */
    atomic_store(&sticks[port], (int32_t) ((uint32_t) (uint16_t) lx << 16 | (uint16_t) ly));
    atomic_store(&rsticks[port], (int32_t) ((uint32_t) (uint16_t) rx << 16 | (uint16_t) ry));
}

static void input_poll(void) {
    for (int p = 0; p < PORTS; p++) seen[p] = atomic_load(&held[p]) | atomic_exchange(&latched[p], 0);
}

static int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port >= PORTS) return 0;
    if (device == RETRO_DEVICE_JOYPAD) {
        if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t) (seen[port] & 0xFFFF);
        return id < 16 && (seen[port] >> id & 1) ? 1 : 0;
    }
    if (device == RETRO_DEVICE_ANALOG) {
        if (index == RETRO_DEVICE_INDEX_ANALOG_BUTTON)      /* analog triggers: all or nothing */
            return id < 16 && (seen[port] >> id & 1) ? 0x7FFF : 0;
        int32_t v = index == RETRO_DEVICE_INDEX_ANALOG_LEFT ? atomic_load(&sticks[port]) : atomic_load(&rsticks[port]);
        return id == RETRO_DEVICE_ID_ANALOG_X ? (int16_t) ((uint32_t) v >> 16) : (int16_t) (v & 0xFFFF);
    }
    return 0;
}

/* ---- files ---- */
static void *read_file(const char *path, size_t *size) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    void *buf = n > 0 ? malloc((size_t) n) : NULL;
    if (buf && fread(buf, 1, (size_t) n, f) != (size_t) n) {
        free(buf);
        buf = NULL;
    }
    fclose(f);
    if (buf) *size = (size_t) n;
    return buf;
}

/* written to a part file first, then put in place: a crash never leaves half a save */
static int write_file(const char *path, const void *data, size_t size) {
    char part[2100];
    snprintf(part, sizeof part, "%s.part", path);
    FILE *f = fopen(part, "wb");
    if (!f) return -1;
    int ok = fwrite(data, 1, size, f) == size;
    ok = fflush(f) == 0 && ok;
    fsync(fileno(f));
    fclose(f);
    if (!ok || rename(part, path) != 0) {
        unlink(part);
        return -1;
    }
    return 0;
}

static void save_path(char *out, size_t len, const char *ext) {
    snprintf(out, len, "%s/%s%s", save_dir, base_name, ext);
}

static uint8_t *sram_was;
static size_t sram_was_size;

static void load_sram(void) {
    size_t cap = core_get_memory_size ? core_get_memory_size(RETRO_MEMORY_SAVE_RAM) : 0;
    void *mem = cap ? core_get_memory_data(RETRO_MEMORY_SAVE_RAM) : NULL;
    if (!mem) return;
    char path[2048];
    save_path(path, sizeof path, ".srm");
    size_t n = 0;
    void *data = read_file(path, &n);
    if (data) {
        memcpy(mem, data, n < cap ? n : cap);
        free(data);
        say("[choccy] game save %s", path);
    }
    sram_was = malloc(cap);
    if (sram_was) {
        memcpy(sram_was, mem, cap);
        sram_was_size = cap;
    }
}

int cm_save_sram(void) {
    if (!loaded) return 0;
    size_t cap = core_get_memory_size ? core_get_memory_size(RETRO_MEMORY_SAVE_RAM) : 0;
    void *mem = cap ? core_get_memory_data(RETRO_MEMORY_SAVE_RAM) : NULL;
    if (!mem) return 0;
    if (sram_was && sram_was_size == cap && !memcmp(sram_was, mem, cap)) return 0;
    char path[2048];
    save_path(path, sizeof path, ".srm");
    if (write_file(path, mem, cap) != 0) {
        say("[choccy] can't write %s: %s", path, strerror(errno));
        return -1;
    }
    if (sram_was_size != cap) {
        free(sram_was);
        sram_was = malloc(cap);
        sram_was_size = sram_was ? cap : 0;
    }
    if (sram_was) memcpy(sram_was, mem, cap);
    return 1;
}

int cm_state(int save, int slot) {
    if (!loaded || slot < 0 || slot > 9) return -1;
    char ext[16], path[2048];
    if (slot == 0) snprintf(ext, sizeof ext, ".state");
    else snprintf(ext, sizeof ext, ".state%d", slot);
    save_path(path, sizeof path, ext);
    char text[64];
    if (save) {
        size_t n = core_serialize_size();
        void *buf = n ? malloc(n) : NULL;
        int ok = buf && core_serialize(buf, n) && write_file(path, buf, n) == 0;
        free(buf);
        snprintf(text, sizeof text, ok ? "State %d saved" : "State %d couldn't be saved", slot);
        if (host.notice) host.notice(host.user, text);
        return ok ? 0 : -1;
    }
    size_t n = 0;
    void *buf = read_file(path, &n);
    int ok = buf && core_unserialize(buf, n);
    free(buf);
    snprintf(text, sizeof text, ok ? "State %d loaded" : buf ? "State %d didn't load" : "No state %d yet", slot);
    if (host.notice) host.notice(host.user, text);
    return ok ? 0 : -1;
}

/* ---- loading ---- */
#define FIND(name, field)                                                    \
    do {                                                                     \
        *(void **) &field = dlsym(lib, name);                                \
        if (!field) {                                                        \
            snprintf(err, errlen, "not a libretro core (no %s)", name);      \
            goto fail;                                                       \
        }                                                                    \
    } while (0)

int cm_load(const char *core, const char *rom, const char *saves, const char *system, const char *options,
            const cm_host *h, char *err, size_t errlen) {
    host = *h;
    snprintf(save_dir, sizeof save_dir, "%s", saves);
    snprintf(system_dir, sizeof system_dir, "%s", system);
    const char *slash = strrchr(rom, '/');
    snprintf(base_name, sizeof base_name, "%s", slash ? slash + 1 : rom);
    char *dot = strrchr(base_name, '.');
    if (dot && dot != base_name) *dot = 0;
    parse_overrides(options);

    lib = dlopen(core, RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        snprintf(err, errlen, "can't load the core: %s", dlerror());
        return -1;
    }
    FIND("retro_set_environment", core_set_environment);
    FIND("retro_set_video_refresh", core_set_video);
    FIND("retro_set_audio_sample", core_set_audio);
    FIND("retro_set_audio_sample_batch", core_set_audio_batch);
    FIND("retro_set_input_poll", core_set_input_poll);
    FIND("retro_set_input_state", core_set_input_state);
    FIND("retro_init", core_init);
    FIND("retro_deinit", core_deinit);
    FIND("retro_get_system_info", core_get_system_info);
    FIND("retro_get_system_av_info", core_get_av_info);
    FIND("retro_load_game", core_load_game);
    FIND("retro_unload_game", core_unload_game);
    FIND("retro_run", core_run);
    FIND("retro_serialize_size", core_serialize_size);
    FIND("retro_serialize", core_serialize);
    FIND("retro_unserialize", core_unserialize);
    FIND("retro_get_memory_data", core_get_memory_data);
    FIND("retro_get_memory_size", core_get_memory_size);

    core_set_environment(environment);
    core_init();
    inited = 1;
    struct retro_system_info info;
    memset(&info, 0, sizeof info);
    core_get_system_info(&info);
    snprintf(core_name, sizeof core_name, "%s %s", info.library_name ? info.library_name : "?",
             info.library_version ? info.library_version : "");
    core_set_video(video);
    core_set_audio(audio_sample);
    core_set_audio_batch(audio_batch);
    core_set_input_poll(input_poll);
    core_set_input_state(input_state);

    struct retro_game_info game = {rom, NULL, 0, NULL};
    if (!info.need_fullpath) {                               /* the core wants the game in memory */
        size_t n = 0;
        rom_data = read_file(rom, &n);
        if (!rom_data) {
            snprintf(err, errlen, "can't read %s: %s", rom, strerror(errno));
            goto fail;
        }
        game.data = rom_data;
        game.size = n;
    }
    if (!core_load_game(&game)) {
        snprintf(err, errlen, "%s didn't take the game (wrong console, a missing BIOS, or a core that needs a "
                              "graphics chip)", core_name);
        goto fail;
    }
    loaded = 1;
    struct retro_system_av_info a;
    memset(&a, 0, sizeof a);
    core_get_av_info(&a);
    read_av(&a);
    load_sram();
    say("[choccy] %s: %dx%d at %.2f frames a second, sound at %.0f Hz", core_name, av.width, av.height, av.fps, av.sample_rate);
    return 0;

fail:
    if (inited) core_deinit();
    inited = 0;
    dlclose(lib);
    lib = NULL;
    return -1;
}

const char *cm_core_name(void) {
    return core_name;
}

cm_av cm_get_av(void) {
    return av;
}

void cm_run(void) {
    if (!loaded) return;
    sound_frames = 0;
    core_run();
    if (sound_frames && host.audio) host.audio(host.user, sound, sound_frames);
}

void cm_unload(void) {
    if (loaded) {
        cm_save_sram();
        core_unload_game();
        loaded = 0;
    }
    if (inited) core_deinit();
    inited = 0;
    /* the core stays loaded: some don't survive being unloaded and loaded again in one process */
    free(rom_data);
    rom_data = NULL;
    free(sram_was);
    sram_was = NULL;
    sram_was_size = 0;
    for (int i = 0; i < n_overrides; i++) {
        free(overrides[i].key);
        free(overrides[i].value);
    }
    n_overrides = 0;
    for (int i = 0; i < n_defaults; i++) {
        free(defaults[i].key);
        free(defaults[i].value);
    }
    n_defaults = 0;
}
