#!/usr/bin/env python3
"""Speaks the narration listed in trailer2.html (VOICE) with the Kokoro voice model, via sherpa-onnx.

usage: voice.py <trailer2.html> <kokoro model dir> <out dir> [speaker id]
Writes <out>/<id>.wav per line and prints {"id": seconds, ...}.
"""
import json
import re
import sys

import sherpa_onnx
import soundfile as sf

html, model, out = sys.argv[1:4]
speaker = int(sys.argv[4]) if len(sys.argv) > 4 else 6          # am_michael
lines = re.findall(r'\["(v\d+)",\s*[\d.]+,\s*"([^"]+)"', open(html, encoding="utf-8").read())
tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
    kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(model=f"{model}/model.int8.onnx", voices=f"{model}/voices.bin",
                                                   tokens=f"{model}/tokens.txt", data_dir=f"{model}/espeak-ng-data"),
    num_threads=4)))
lengths = {}
for vid, text in lines:
    audio = tts.generate(text, sid=speaker, speed=1.0)
    sf.write(f"{out}/{vid}.wav", audio.samples, audio.sample_rate)
    lengths[vid] = round(len(audio.samples) / audio.sample_rate, 2)
print(json.dumps(lengths))
