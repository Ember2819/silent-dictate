# Silent Dictate

An Android keyboard with two modes:

- **Type** — a minimal, monochrome QWERTY you switch to like any keyboard.
  Long-press the top row for digits, `?123` → `=\<` for a second symbols
  page, double-tap shift for caps lock, double-space for a period, long-press
  space to switch keyboards.
- **Dictate silently** — swipe **left** anywhere on the keys. A single
  microphone-style button appears. **Hold** it and the front camera records your
  lips; a small on-device lip-reading model turns lip movement into raw text,
  an OpenRouter LLM (optional, recommended) reconstructs the intended sentence,
  and the result lands in whatever text field you're on. Swipe **right** to go
  back to the keys.

No sound is needed. Video is never stored or uploaded; only the final
transcript is sent out, and only if you turn OpenRouter cleanup on.

> The lip reader is a real, published model — Auto-AVSR's LRS3 visual-only
> recogniser (203 MB, int8 ONNX) — downloaded from inside the app with one tap.
> It is licensed for **research and non-commercial use only**; see
> [The lip model](#the-lip-model).

---

## What's in this repo

```
app/src/main/java/app/silentdictate/
  ime/SilentDictateImeService.kt   the input method, mode switching, animations
  ime/KeyboardView.kt              custom-drawn QWERTY (no libraries for keys)
  ime/MicPanel.kt                  the hold-to-record circle, pulse/progress
  lips/DictationController.kt      camera + model + LLM orchestration
  lips/FaceAlign.kt                the model's training-time mouth-crop geometry
  lips/MouthAnalyzer.kt            ML Kit face tracking -> aligned mouth crop per frame
  lips/Frames.kt                   resample to 25 fps, sentence-case the output
  lips/ImageOps.kt                 YUV → upright frame → bilinear aligned crop
  lips/model/LipReaderEngine.kt    ONNX Runtime CTC inference
  lips/model/ModelManager.kt       model zip download / status
  ai/OpenRouterClient.kt           chat-completions cleanup request
  settings/SettingsActivity.kt     onboarding + settings, same design language
tools/export_lip_model.py          export a VSR checkpoint for the app
```

## Build

```bash
./gradlew assembleDebug     # or open in Android Studio
```

Requires JDK 17. Output: `app/build/outputs/apk/debug/` — you get per-ABI APKs
(`app-arm64-v8a-debug.apk` for most phones, `app-x86_64-debug.apk` for
emulators) and a big `app-universal-debug.apk`.

Then run `./tools/resign.sh` — Samsung's sideload installer rejects
v2-only builds; this re-signs everything with v1+v2 signatures.

## Releases

Signed APKs are built by GitHub Actions (`.github/workflows/release.yml`):
```bash
git tag v0.2.0 && git push origin v0.2.0
```

builds `assembleRelease`, signs it, and publishes a GitHub release with
`silent-dictate-arm64-v8a.apk` (most phones), `-x86_64.apk` (emulators),
`-universal.apk`, and `SHA256SUMS.txt`. The version name comes from the tag.
`dist/index.html` always links to the latest arm64 APK.

Signing uses four repository secrets: `RELEASE_KEYSTORE_BASE64` (the `.jks`,
base64-encoded), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD`. Locally, export the same names (with
`RELEASE_KEYSTORE_PATH` instead of the base64 one) to build a signed release.
**Keep the keystore safe:** Android only installs updates signed with the same
key, so losing it means users must uninstall to upgrade.

**In-app updates:** the settings screen has an Updates card that checks this
repo's releases (via the `releases/latest` redirect, no API key), downloads the
APK matched to the phone's ABI, and hands it to the system package installer.


CI (`.github/workflows/ci.yml`) builds debug APKs, runs lint and compiles the
instrumented tests on every push and pull request. The real-video tests need an
emulator and a 210 MB download, so they run locally: `./tools/real_video_test.sh`.

## First run (onboarding)

1. Install the app, open **Silent Dictate**.
2. Enable the keyboard in system settings.
3. Switch to it (the button opens the system keyboard picker).
4. Allow the camera (only used while the dictation page is open).
5. Add the lip model — **Download** (203 MB, best on Wi-Fi).
6. Transcription → **OpenRouter** (recommended), paste your API key. The model
   defaults to a small, fast free model. "Check key" sends a tiny test request.
   Pick **Raw output** to insert the lip reader's text as-is (it's inaccurate
   and unreliable on its own).

Once the keyboard is enabled, a "try the keyboard" field appears in the setup
card so you can test right there.

## The lip model

**What it is.** [`eschmechel/auto-avsr-lrs3-vsr-int8-onnx`](https://huggingface.co/eschmechel/auto-avsr-lrs3-vsr-int8-onnx):
the encoder and CTC head of Auto-AVSR's `LRS3_V_WER19.1` visual-only recogniser
([paper/code](https://github.com/mpc001/auto_avsr), Ma et al., ICASSP 2023),
exported to ONNX and int8-quantised. The app downloads it, checks its SHA-256
(`da02d72e…f195b`), builds `config.json`/`vocab.txt` from the published
`tokens.json`, loads it and runs a dummy clip through it, and only then swaps it
in. Settings → Lip model → **Download**.

**How good is it.** The model card reports ~28.5% word error rate on LRS3 test
clips with plain greedy decoding (no language model, no beam search) — that is
why the OpenRouter cleanup exists. On this repo's own end-to-end test (a
frontal, well-lit talking-head clip, 32 words, through the real Android code)
it scores **3.1% WER**: one word wrong. Expect worse in poor light, at an angle,
with a beard or a hand near the mouth, or with mumbled speech. It reads English
only, from the video alone.

**What it asks of the phone.**
- 203 MB of storage, and a few hundred MB of RAM while the dictation page is
  open (the model is unloaded when you swipe back to the keys). Memory grows with
  clip length (about 700 MB at 8 s), and the model refuses anything over 500
  frames (20 s). So longer dictation is read in pieces of at most 7 s, each cut
  where the mouth moves least, usually a pause between words (`Chunker` in
  `lips/Frames.kt`). On a 20 s test clip that cost about one word in sixty
  compared with reading it whole. A single hold is capped at 60 s.
- Inference took about 6 s for 10 s of video on the x86 *emulator*. Phone speed
  is **untested** here.
- Capture is resampled to the model's 25 fps; accuracy falls off quickly below that.

**Licence.** The weights inherit the LRS3 (BBC/TED) terms: research and
non-commercial use only. Auto-AVSR's code is Apache-2.0. Don't ship this app
commercially with this model.

### How the crop works

The model was trained on 88×88 grayscale patches cut like this: fit a
similarity transform from four face points (eyes, nose base, mouth centre) onto
a canonical 256-px face, then take a window centred on the mouth
(`lips/FaceAlign.kt`). The app gets the points from ML Kit, smooths the
transform, and samples the window bilinearly from the camera's Y plane. A plain
"box around the lips" crop has the wrong scale and ignores head tilt.

### Testing the real pipeline

```bash
./tools/real_video_test.sh   # needs adb + emulator/device, ffmpeg, yt-dlp (~210 MB download)
```

It pushes a public-domain talking-head video (as frames) and the model to the
device and runs two instrumented tests that score the result against the
video's captions: one feeds frames through ML Kit → crop → model → decoder, the
other drives the production `MouthAnalyzer` with sensor-rotated, padded
`YUV_420_888` frames at 30 fps, like CameraX. What they do **not** cover: a real
camera, real phone performance, or lighting/pose variety.

### Using a different model

Settings → Lip model → **Use your own model (.zip)**: a zip with `model.onnx`,
`config.json`, `vocab.txt` (see `tools/model_config.example.json`). It must take
`[1, C, T, 88, 88]`-style grayscale mouth crops normalised with `mean`/`std` and
return CTC logits; the crop geometry is the Auto-AVSR one above.
`tools/export_lip_model.py` is an unverified **template** for exporting such a
checkpoint, and `tools/make_test_model.py` builds a synthetic zip that exercises
install/inference only (it does not read lips).

### Raw mode (cleanup off)

You can turn OpenRouter cleanup off in settings. The raw lip-reading output is
inserted as plain sentence-case text (the model itself writes capitals only).
Be aware: on its own it is **inaccurate and unreliable** — missing and mangled
words are normal. It's also fully offline, which is the trade-off.

## Privacy

- The camera is open only while the dictation page is on screen, and frames are
  only kept while you hold the button. Android's camera indicator shows when
  it's on.
- With cleanup on, only the raw transcript + a short tail of the text before
  your cursor is sent to openrouter.ai. No analytics, no other network use.
- Dictation is disabled in password fields, and nothing is ever read from them.
- Nothing you type is stored.

## Palette / design

The whole app (keyboard and settings) is near-monochrome: `#101113` background,
`#17191C` cards, `#ECEDEF` for text and the one emphasised control per screen
(the active segment, the next setup step, the enter key, the held mic button),
`#8A8F98` secondary, and a single cautious `#E5726E` that appears only for
errors. No other accent colors. Two page dots at the top of the keyboard show
that dictation is one swipe away; mode changes slide and cross-fade in ~200 ms.
