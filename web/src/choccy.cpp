// Choccy Milk's browser libretro host. The desktop host uses Java FFM;
// this host implements the same software-video, audio, input and save contract.
#include <emscripten.h>
#include <vector>
#include <map>
#include <string>
#include <cstring>
#include <cstdint>
#include <algorithm>
#include <sys/stat.h>
#include "libretro.h"
#define API extern "C" EMSCRIPTEN_KEEPALIVE
static std::vector<uint32_t> pixels;
static std::vector<int16_t> samples;
static std::vector<unsigned char> game;
static std::map<std::string, std::string> options;
static unsigned width=0,height=0,format=0,buttons[4]={};
static int16_t axes[4][2][2]={},px=0,py=0;
static bool pressed=false,loaded=false,initialized=false;
static double rate=48000,fps=60;
static float aspect=0;
static void variable(const char *key,const char *value) {
 if (!key || !value) return;
 const char *start=strstr(value,"; "); start=start ? start+2 : value;
 std::string s(start); options[key]=s.substr(0,s.find('|'));
}
static bool environment(unsigned cmd,void *data) {
 switch(cmd) {
 case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: format=*(unsigned*)data; return format<=2;
 case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
 case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *(const char**)data="/system";return true;
 case RETRO_ENVIRONMENT_GET_CAN_DUPE: *(bool*)data=true;return true;
 case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: return true;
 case RETRO_ENVIRONMENT_GET_LANGUAGE: *(unsigned*)data=RETRO_LANGUAGE_ENGLISH;return true;
 case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION: *(unsigned*)data=0;return true;
 case RETRO_ENVIRONMENT_SET_VARIABLES:
  for(auto v=(retro_variable*)data;v && v->key;++v) variable(v->key,v->value);return true;
 case RETRO_ENVIRONMENT_GET_VARIABLE: {
  auto v=(retro_variable*)data; auto it=options.find(v->key);
  v->value=it==options.end()?nullptr:it->second.c_str();return v->value!=nullptr;
 }
 case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *(bool*)data=false;return true;
 case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
 case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
 case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO: return true;
 case RETRO_ENVIRONMENT_SET_GEOMETRY: aspect=((retro_game_geometry*)data)->aspect_ratio;return true;
 default:return false;
 }
}
static void video(const void *data,unsigned w,unsigned h,size_t pitch) {
 if(!data || data==RETRO_HW_FRAME_BUFFER_VALID || !w || !h || w>2048 || h>2048)return;
 width=w;height=h;pixels.resize(w*h);
 for(unsigned y=0;y<h;++y)for(unsigned x=0;x<w;++x) {
  auto row=(const unsigned char*)data+y*pitch;
  unsigned c,r,g,b;
  if(format==RETRO_PIXEL_FORMAT_XRGB8888){memcpy(&c,row+x*4,4);r=(c>>16)&255;g=(c>>8)&255;b=c&255;}
  else {uint16_t v;memcpy(&v,row+x*2,2);c=v;
   r=((c>>(format==RETRO_PIXEL_FORMAT_RGB565?11:10))&31)*255/31;
   g=((c>>5)&(format==RETRO_PIXEL_FORMAT_RGB565?63:31))*255/(format==RETRO_PIXEL_FORMAT_RGB565?63:31);
   b=(c&31)*255/31;}
  pixels[y*w+x]=0xff000000|(b<<16)|(g<<8)|r;
 }
}
static size_t audio(const int16_t *data,size_t frames) {
 size_t count=std::min(frames*2,size_t(32768)-std::min(samples.size(),size_t(32768)));
 samples.insert(samples.end(),data,data+count);return frames;
}
static void sample(int16_t l,int16_t r){int16_t pair[]={l,r};audio(pair,1);}
static void poll(){}
static int16_t input(unsigned port,unsigned device,unsigned index,unsigned id){
 if(port>=4)return 0;
 if(device==RETRO_DEVICE_JOYPAD)return id==RETRO_DEVICE_ID_JOYPAD_MASK ? (int16_t)buttons[port] : id<16 && (buttons[port]&(1u<<id))?1:0;
 if(device==RETRO_DEVICE_ANALOG && index<2 && id<2)return axes[port][index][id];
 if(device==RETRO_DEVICE_POINTER)return id==0?px:id==1?py:id==2?pressed:0;
 return 0;
}
API void cm_stop(){if(loaded)retro_unload_game();if(initialized)retro_deinit();loaded=initialized=false;game.clear();pixels.clear();samples.clear();width=height=0;memset(buttons,0,sizeof buttons);}
API int cm_load(const unsigned char *data,size_t length){
 cm_stop();if(!data || !length || length>64*1024*1024)return 0;
 options.clear();format=0;mkdir("/system",0700);
 retro_set_environment(environment);retro_set_video_refresh(video);retro_set_audio_sample(sample);
 retro_set_audio_sample_batch(audio);retro_set_input_poll(poll);retro_set_input_state(input);
 retro_init();initialized=true;game.assign(data,data+length);
 retro_game_info info={"/game.gb",game.data(),game.size(),nullptr};
 loaded=retro_load_game(&info);if(!loaded){cm_stop();return 0;}
 retro_system_av_info av={};retro_get_system_av_info(&av);
 fps=av.timing.fps;rate=av.timing.sample_rate;aspect=av.geometry.aspect_ratio;
 retro_set_controller_port_device(0,RETRO_DEVICE_JOYPAD);return 1;
}
API void cm_run(){samples.clear();if(loaded)retro_run();}
API void cm_reset(){if(loaded)retro_reset();}
API void cm_buttons(unsigned port,unsigned mask){if(port<4)buttons[port]=mask;}
API void cm_axis(unsigned port,unsigned stick,int x,int y){if(port<4&&stick<2){axes[port][stick][0]=x;axes[port][stick][1]=y;}}
API void cm_pointer(int x,int y,int down){px=x;py=y;pressed=down;}
API const void *cm_pixels(){return pixels.data();}
API unsigned cm_width(){return width;}
API unsigned cm_height(){return height;}
API float cm_aspect(){return aspect;}
API double cm_fps(){return fps;}
API double cm_rate(){return rate;}
API const void *cm_audio(){return samples.data();}
API unsigned cm_audio_frames(){return samples.size()/2;}
API size_t cm_state_size(){return loaded?retro_serialize_size():0;}
API int cm_save(void *data,size_t size){return loaded&&retro_serialize(data,size);}
API int cm_restore(const void *data,size_t size){return loaded&&retro_unserialize(data,size);}
API void *cm_sram(){return loaded?retro_get_memory_data(RETRO_MEMORY_SAVE_RAM):nullptr;}
API size_t cm_sram_size(){return loaded?retro_get_memory_size(RETRO_MEMORY_SAVE_RAM):0;}
// Browser shell navigation lives in WebAssembly too. Presentation uses browser APIs.
static int count=1,selected=0;
API void ui_count(int n){count=std::max(1,n);selected=std::min(selected,count-1);}
API int ui_select(int n){selected=(n%count+count)%count;return selected;}
API int ui_move(int dx,int dy,int columns){return ui_select(selected+dx+dy*columns);}
API int ui_selected(){return selected;}
