#!/bin/sh
# Renders the setup guide mascot's voice (resources/voice/*.wav) from scripts/voice-lines.txt
# ("id|text to say"; the name is spelled the way it sounds). Uses Piper (pip install piper-tts)
# with the en_US-ljspeech-high voice (trained on the public-domain LJ Speech dataset) and ffmpeg,
# which raises the pitch a little and stores 22 kHz mono mu-law WAVs that Java plays as they are.
#   sh scripts/make-voice.sh /path/to/en_US-ljspeech-high.onnx
set -e
MODEL=${1:?usage: make-voice.sh MODEL.onnx}
cd "$(dirname "$0")/.."
mkdir -p resources/voice
tmp=$(mktemp -d)
while IFS='|' read -r id text <&3; do
  [ -n "$id" ] || continue
  echo "$text" | piper -m "$MODEL" --length-scale 0.95 --sentence-silence 0.25 -f "$tmp/$id.wav" >/dev/null 2>&1
  ffmpeg -nostdin -loglevel error -y -i "$tmp/$id.wav" \
    -af "asetrate=22050*1.12,aresample=22050,silenceremove=start_periods=1:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_threshold=-50dB,areverse,loudnorm=I=-16:TP=-1.5" \
    -ar 22050 -ac 1 -c:a pcm_mulaw "resources/voice/$id.wav"
  echo "$id"
done 3< scripts/voice-lines.txt
rm -rf "$tmp"
