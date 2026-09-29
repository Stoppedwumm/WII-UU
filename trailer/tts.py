#!/usr/bin/env python3
"""Speaks the lines of youtube.json, one audio file per line (y1, y2, ... in order).

usage: tts.py <script.json> <out dir> --engine kokoro|elevenlabs [--model DIR] [--voice ID]
  kokoro:     offline, needs sherpa-onnx and the Kokoro model directory (--model), voice = speaker number
  elevenlabs: needs the API key in ELEVENLABS (or ELEVENLABS_API_KEY / ELEVENLABS_KEY_FILE); voice = voice id
Prints {"y1": seconds, ...}. Each line is its own request; ElevenLabs gets the neighbouring
lines as context so the delivery flows like one read.
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.request

p = argparse.ArgumentParser()
p.add_argument("script")
p.add_argument("out")
p.add_argument("--engine", default="kokoro")
p.add_argument("--model", default="")
p.add_argument("--voice", default="")
a = p.parse_args()

lines = [l["say"] for s in json.load(open(a.script, encoding="utf-8"))["sections"] for l in s["lines"]]
os.makedirs(a.out, exist_ok=True)


def duration(path):
    out = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                         capture_output=True, text=True).stdout.strip()
    return round(float(out), 2)


lengths = {}
if a.engine == "elevenlabs":
    key = os.environ.get("ELEVENLABS") or os.environ.get("ELEVENLABS_API_KEY")
    if not key and os.environ.get("ELEVENLABS_KEY_FILE"):
        key = open(os.environ["ELEVENLABS_KEY_FILE"]).read().strip()
    if not key:
        sys.exit("no ElevenLabs key (ELEVENLABS or ELEVENLABS_KEY_FILE)")
    voice = a.voice or "nPczCjzI2devNBz1zQrb"      # "Brian": a warm, clear narrator
    for i, text in enumerate(lines):
        body = {"text": text, "model_id": "eleven_multilingual_v2",
                "voice_settings": {"stability": 0.5, "similarity_boost": 0.8, "style": 0.25, "use_speaker_boost": True},
                "previous_text": lines[i - 1] if i > 0 else None, "next_text": lines[i + 1] if i + 1 < len(lines) else None}
        req = urllib.request.Request(f"https://api.elevenlabs.io/v1/text-to-speech/{voice}?output_format=mp3_44100_128",
                                     data=json.dumps(body).encode(), headers={"xi-api-key": key, "Content-Type": "application/json"})
        path = f"{a.out}/y{i + 1}.mp3"
        with urllib.request.urlopen(req, timeout=120) as r, open(path, "wb") as f:
            f.write(r.read())
        # plain WAV for the mixer
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", path, f"{a.out}/y{i + 1}.wav"], check=True)
        lengths[f"y{i + 1}"] = duration(f"{a.out}/y{i + 1}.wav")
else:
    import sherpa_onnx
    import soundfile as sf
    m = a.model
    tts = sherpa_onnx.OfflineTts(sherpa_onnx.OfflineTtsConfig(model=sherpa_onnx.OfflineTtsModelConfig(
        kokoro=sherpa_onnx.OfflineTtsKokoroModelConfig(model=f"{m}/model.int8.onnx", voices=f"{m}/voices.bin",
                                                       tokens=f"{m}/tokens.txt", data_dir=f"{m}/espeak-ng-data"),
        num_threads=4)))
    sid = int(a.voice) if a.voice else 6
    for i, text in enumerate(lines):
        audio = tts.generate(text, sid=sid, speed=1.0)
        sf.write(f"{a.out}/y{i + 1}.wav", audio.samples, audio.sample_rate)
        lengths[f"y{i + 1}"] = round(len(audio.samples) / audio.sample_rate, 2)
print(json.dumps(lengths))
