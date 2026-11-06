/* Minimal redistributable libretro fixture: DS-shaped frames and a native stdout banner. */
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

typedef bool (*environment_t)(unsigned, void *);
typedef void (*video_t)(const void *, unsigned, unsigned, size_t);
static environment_t environment;
static video_t video;
static uint32_t pixels[256 * 384];
struct system_info { const char *name, *version, *extensions; bool need_fullpath, block_extract; };
struct game_info { const char *path; const void *data; size_t size; const char *meta; };
struct geometry { unsigned width, height, max_width, max_height; float aspect; };
struct av_info { struct geometry geometry; struct { double fps, sample_rate; } timing; };
__attribute__((constructor)) static void banner(void) { puts("native DS fixture banner"); fflush(stdout); }
unsigned retro_api_version(void) { return 1; }
void retro_set_environment(environment_t cb) { environment = cb; }
void retro_set_video_refresh(video_t cb) { video = cb; }
void retro_set_audio_sample(void *cb) { (void) cb; }
void retro_set_audio_sample_batch(void *cb) { (void) cb; }
void retro_set_input_poll(void *cb) { (void) cb; }
void retro_set_input_state(void *cb) { (void) cb; }
void retro_init(void) { int format = 1; environment(10, &format); }
void retro_get_system_info(struct system_info *info) { *info = (struct system_info){"DS startup fixture", "1", "nds", true, false}; }
bool retro_load_game(const struct game_info *game) {
    (void) game;
    for (unsigned i = 0; i < 256 * 384; i++) pixels[i] = i < 256 * 192 ? 0x112233 : 0x445566;
    return true;
}
void retro_get_system_av_info(struct av_info *av) { *av = (struct av_info){{256, 384, 256, 384, 2.0f/3}, {60, 48000}}; }
void *retro_get_memory_data(unsigned type) { (void) type; return NULL; }
size_t retro_get_memory_size(unsigned type) { (void) type; return 0; }
size_t retro_serialize_size(void) { return 0; }
bool retro_serialize(void *data, size_t size) { (void) data; (void) size; return false; }
bool retro_unserialize(const void *data, size_t size) { (void) data; (void) size; return false; }
void retro_run(void) { video(pixels, 256, 384, 256 * sizeof(uint32_t)); }
void retro_unload_game(void) {}
void retro_deinit(void) {}
