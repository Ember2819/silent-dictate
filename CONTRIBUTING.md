# Contributing

Thanks for helping make silent dictation real.

## Getting started

1. Fork + clone your fork.
2. `./gradlew assembleDebug` (JDK 17) or open in Android Studio.
3. Install `app-debug.apk` on a device, complete the in-app onboarding
   (enable the keyboard, pick it, grant camera).

## Model exports are inbox-design

`LipReaderEngine` only knows the ONNX tensor contract in `config.json`.
If you export/checkpoint-pack a small CTC VSR model that loads and decodes,
PRs to `tools/export_lip_model.py` and `tools/model_config.example.json` are
extremely welcome — that's the main piece this repo cannot ship.

## Ground rules

- Keep the UI near-monochrome and quiet. If you're reaching for a new color,
  you probably want a different font weight.
- Camera frames must never leave the device. The only outbound data is the
  optional OpenRouter cleanup call (transcript + text-tail) — don't add
  an endpoint or a dependency that changes that invariant.
- Match the existing code style; small focused PRs.
