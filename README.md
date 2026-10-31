# Silent Dictate

An Android keyboard with two modes:

- **Type** — a minimal, monochrome QWERTY you switch to like any keyboard.
- **Dictate silently** — swipe **left** anywhere on the keys. A single
  microphone-style button appears. **Hold** it and the front camera records your
  lips; a small on-device lip-reading model turns lip movement into raw text,
  an OpenRouter LLM (optional, recommended) reconstructs the intended sentence,
  and the result lands in whatever text field you're on.

No sound is needed. Nothing is photographed or uploaded; only the final
transcript is sent out, and only if you turn OpenRouter cleanup on.

---

## What's in this repo

```
app/src/main/java/app/silentdictate/
  ime/SilentDictateImeService.kt   the input method, mode switching, animations
  ime/KeyboardView.kt              custom-drawn QWERTY (no libraries for keys)
  ime/MicPanel.kt                  the hold-to-record circle, pulse/progress
  lips/DictationController.kt      camera + model + LLM orchestration
  lips/MouthAnalyzer.kt            ML Kit face tracking, mouth ROI per frame
  lips/ImageOps.kt                 YUV → upright frame → mouth sampling math
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

## First run (onboarding)

1. Install the app, open **Silent Dictate**.
2. Step 1 — enable the keyboard in system settings.
3. Step 2 — pick it while typing (or via the "change keyboard" button).
4. Step 3 — grant camera access (only used while you hold the button).
5. Step 4 — install a lip model (below).
6. Settings → **Clean up with OpenRouter** (recommended), paste your API key,
   optionally edit the model id. "Check key" verifies both.

## The lip model

The movement of the model is:

1. While the hold button is pressed, frames from the **front** camera are
   analyzed on-device: ML Kit tracks your face, and a tight mouth crop is
   sampled from the Y plane of each frame, upright and normalized.
2. The sample stack is fed to a small **CTC VSR model** exported to **ONNX**
   (runs via ONNX Runtime Mobile, CPU only). Output is greedy-decoded.
3. The raw transcript is either inserted as-is, or sent to OpenRouter with a
   short context of what's already in the field; the LLM returns the intended
   sentence, which is inserted.

### Exporting a model

The app is not hardwired to one architecture. It loads from
`{files}/model/`: `model.onnx`, `config.json`, `vocab.txt`
(see `tools/model_config.example.json`). Any small CTC visual-speech model
exported with those rules works — the reference recipe is
[AutoAVSR](https://github.com/mpc002/auto_avsr), whose checkpoints can be
exported with `tools/export_lip_model.py`. Zip the three files, host the zip
anywhere, and paste the URL in Settings → Lip model → Download.

### Raw mode (cleanup off)

You can turn OpenRouter cleanup off in settings. Be aware: the raw lip-reading
output alone is **inaccurate and unreliable** — missing and mangled words are
normal. It's also fully offline, which is the trade-off.

## Privacy

- Camera frames stay on-device and are only sampled while you hold the button.
- With cleanup on, only the raw transcript + a short tail of the current text
  field is sent to openrouter.ai. No analytics, no other network use.

## Palette / design

The whole app (keyboard and settings) is near-monochrome: `#101113` background,
`#17191C` surfaces, `#ECEDEF` text, `#8A8F98` secondary, one cautious `#E5726E`
for errors and `#9BC77F` for confirmations. Animations are short cross-fades
with a small slide (170 ms, decelerate) so mode switching feels like sliding
the keyboard aside.
