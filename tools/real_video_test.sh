#!/usr/bin/env bash
# End-to-end check of the real lip-reading pipeline on real video, on a device or emulator.
#
# Downloads a public-domain White House weekly address (frontal face, clear speech) and
# the model, turns the video into JPEG frames, pushes everything to the device and runs
# the instrumented tests in app/src/androidTest:
#   RealVideoLipReadTest    frames -> ML Kit -> aligned crop -> ONNX Runtime -> text
#                           + a 20 s clip read in pieces split at pauses
#   LiveAnalyzerPathTest    same, but through MouthAnalyzer with sensor-rotated, strided
#                           YUV frames at 30 fps (what CameraX delivers)
# Both must score <= 25% word error rate against the video's captions.
#
# Needs: adb with a device/emulator, ffmpeg, yt-dlp, curl. ~210 MB download, cached in $WORK.
set -euo pipefail
cd "$(dirname "$0")/.."
WORK=${WORK:-/tmp/silent-dictate-realtest}
ADB=${ADB:-$HOME/android-sdk/platform-tools/adb}
HF=https://huggingface.co/eschmechel/auto-avsr-lrs3-vsr-int8-onnx/resolve/main
mkdir -p "$WORK/data"; cd "$WORK"

[ -f clip.mp4 ] || yt-dlp -q --no-warnings -f 136 -o clip.mp4 "https://www.youtube.com/watch?v=2AFpAATHXtc"
[ -f data/lipread_ctc.int8.onnx ] || curl -L --fail -o data/lipread_ctc.int8.onnx "$HF/lipread_ctc.int8.onnx"
[ -f data/tokens.json ] || curl -L --fail -o data/tokens.json "$HF/tokens.json"
echo "da02d72ee472eb3d7d50f7dd309bfe81973acb4cac8bb770dd86aa97872f195b  data/lipread_ctc.int8.onnx" | sha256sum -c -

# speech starts ~10 s in; 25 fps for one test, 30 fps (camera-like) for the other
rm -rf data/frames data/frames30 data/frames_long; mkdir -p data/frames data/frames30 data/frames_long
ffmpeg -v error -ss 10 -t 10.5 -i clip.mp4 -vf "fps=25,scale=640:-2" -q:v 3 data/frames/%04d.jpg
ffmpeg -v error -ss 10 -t 10.4 -i clip.mp4 -vf "fps=30,scale=640:-2" -q:v 3 data/frames30/%04d.jpg
# 20.6 s: longer than the model accepts in one go, so it exercises splitting at pauses
ffmpeg -v error -ss 10 -t 20.6 -i clip.mp4 -vf "fps=25,scale=640:-2" -q:v 3 data/frames_long/%04d.jpg

"$ADB" shell rm -rf /data/local/tmp/lip
"$ADB" push data /data/local/tmp/lip >/dev/null
cd - >/dev/null
./gradlew connectedDebugAndroidTest
"$ADB" logcat -d -s LIPTEST LIVETEST | grep -E "HYP|REF|WER"
