#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Install/activate Emscripten 4.0.10 separately, then source emsdk_env.sh.
command -v emcc >/dev/null || { echo 'Activate Emscripten 4.0.10 first.' >&2; exit 1; }
revision=77f2567d718b8973d02caa2daa8dce4953faf811
core=build/web/gambatte
mkdir -p build/web docs/demo/cores
if [ ! -d "$core/.git" ]; then git clone https://github.com/libretro/gambatte-libretro.git "$core"; fi
git -C "$core" fetch origin "$revision"
git -C "$core" checkout --detach "$revision"
emmake make -C "$core" -j"${JOBS:-2}" platform=emscripten CC=emcc CXX=em++ AR=emar HAVE_NETWORK=0
cp "$core/gambatte_libretro_emscripten.bc" build/web/gambatte.a
common="$core/libgambatte/libretro-common"
objects=()
for src in compat/compat_posix_string.c compat/compat_snprintf.c compat/compat_strcasestr.c compat/compat_strl.c compat/fopen_utf8.c encodings/encoding_utf.c file/file_path.c file/file_path_io.c streams/file_stream.c streams/file_stream_transforms.c string/stdstring.c time/rtime.c vfs/vfs_implementation.c; do
  obj="build/web/$(basename "$src").o"
  emcc -O3 -I"$common/include" -c "$common/$src" -o "$obj"
  objects+=("$obj")
done
em++ -O3 web/src/choccy.cpp build/web/gambatte.a "${objects[@]}" \
  -I"$core/libgambatte/libretro" -I"$core/libgambatte/libretro-common/include" \
  -sMODULARIZE=1 -sEXPORT_ES6=1 -sENVIRONMENT=web,node -sALLOW_MEMORY_GROWTH=1 \
  -sINITIAL_MEMORY=33554432 -sMAXIMUM_MEMORY=268435456 -sSTACK_SIZE=1048576 \
  -sEXPORTED_FUNCTIONS='["_malloc","_free"]' -sFILESYSTEM=1 \
  -sEXPORTED_RUNTIME_METHODS=HEAPU8 \
  -o docs/demo/cores/choccy-gambatte.mjs
cp "$core/COPYING" docs/demo/cores/GAMBATTE-LICENSE.txt
python3 web/generate-filters.py
python3 web/make-demo.py
python3 - <<'PY'
import json, re, pathlib
p=pathlib.Path
version=re.search(r'VERSION = "([^"]+)"',p('src/wiiuu/Main.java').read_text())[1]
p('docs/demo/version.json').write_text(json.dumps({'version':version,'channel':'web-preview'})+'\n')
PY
