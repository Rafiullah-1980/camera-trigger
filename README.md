# Voice Trigger Recorder + Key Generator

Two Android apps in one GitHub repo:
- `app/`    Voice Trigger Recorder (3-day trial, then license key)
- `keygen/` Key Generator (separate APK, for you only)
- `common/` shared license code (contains the SECRET)

## Before the first build
1. Open `common/src/main/java/com/rafiullah/common/LicenseCore.java` and change `SECRET`
   to your own long random text. Keep the repo PRIVATE.
2. Push to GitHub -> Actions -> "Build APKs" runs automatically.
3. Download APKs from the run's Artifacts: `VoiceTriggerRecorder-APK`, `KeyGenerator-APK`.

## Giving a key
Customer: install app -> "Copy Mobile ID" -> sends it to you.
You: open Key Generator -> paste ID -> Generate -> send key.
Customer: pastes key -> "Activate key".

Keep `release-fixed.keystore` in the repo: the signing key must stay the same,
otherwise Android gives each build a different Mobile ID.

## Voice Trigger Camera (`camera/`)
Separate app: photo or video, front or back camera, triggered by spoken words.
Uses the same Key Generator: choose "Voice Trigger Camera" there before generating a key.
Keys for the Recorder and the Camera are different and do not open each other.
