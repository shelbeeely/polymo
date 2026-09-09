# PolyMO

**A virtual pet that dies when you doomscroll.**

It is a real object: a palm-sized creature with a bright AMOLED face that sits
on your desk and blinks at you. It gets hungry, it gets bored, and it grows up.
Spend too long in an app it is watching and it falls ill — and it stays ill
until you put the phone down.

Illness is not instant death. A sick pet drains faster than a well one, and a
pet left empty for long enough dies for good, with a reset you have to ask for.
That is the loop: your screen time is the pet's environment, not a setting
inside it.

You talk to it out loud, and it answers out of its own speaker. Show it
something through your phone's camera and it reacts to that too — the phone is
the pet's second brain, not a separate gadget with its own screen to check.

[![I Built a Virtual Pet that Dies when you Doomscroll](https://img.youtube.com/vi/Tyy3dYI-5ds/maxresdefault.jpg)](https://www.youtube.com/watch?v=Tyy3dYI-5ds)

*I Built a Virtual Pet that Dies when you Doomscroll* — the build, and what it
does. Click through to YouTube. Recorded before the Gemini Nano migration
below, so the pipeline it shows (Whisper/llama.cpp/Piper) is the one this app
no longer runs — the loop and the hardware it describes are still current.

Everything it says is generated on your phone, on-device — hearing, thinking
and speaking all run locally through Gemini Nano and the platform's own
text-to-speech. There is no account and no server of PolyMO's own.

**It does talk to Google, though, and the manifest says so rather than hiding
it.** Gemini Nano and ML Kit Vision run on-device, but Android routes their
one-time "is this downloaded" check through Google Play services, which needs
`INTERNET`/`ACCESS_NETWORK_STATE`. That is a real change from an earlier
version of this app, which declared no `INTERNET` permission at all — see
[NOTICE](NOTICE) for exactly what that connectivity is and is not used for.
The full permission list:

```
BLUETOOTH_CONNECT  BLUETOOTH_SCAN  RECORD_AUDIO  CAMERA  PACKAGE_USAGE_STATS
POST_NOTIFICATIONS  RECEIVE_BOOT_COMPLETED  INTERNET  ACCESS_NETWORK_STATE
FOREGROUND_SERVICE  FOREGROUND_SERVICE_CONNECTED_DEVICE
```

`PermissionCopyTest` reads `AndroidManifest.xml` directly and checks the
in-app Permissions page's copy against it, with the manifest wired in as a
declared test input so the check cannot go stale. For a device that listens in
your home, watches through its camera and can read your notifications, a
mechanical check on what the app actually asks for — and what it honestly
says about that — seemed worth having.

## How it works

The pet is the thing you interact with. The phone is the compute it borrows.

```
   ESP32-S3 board                        Android phone
  ┌────────────────┐                  ┌───────────────────────────┐
  │  AMOLED face   │                  │  Gemini Nano / AICore     │
  │  mic + speaker │ ◄─── BLE ──────► │    (hearing + thinking)   │
  │  touch         │      Opus audio  │  platform TextToSpeech    │
  │  the simulation│      + protocol  │    (voice)                │
  └────────────────┘                  │  camera + ML Kit Vision   │
                                       │    (the second brain)    │
                                       │  screen-time sensor      │
                                       └───────────────────────────┘
```

You speak at the pet. The board streams Opus audio to the phone, which
transcribes it with Speech Recognition (Gemini Nano, Advanced mode), asks the
Prompt API for a reply in the pet's voice, synthesises speech with the
platform's own `TextToSpeech`, and streams it back for the pet to play. The
phone never joins the conversation — it has no UI for talking to the pet, only
for setting it up and for showing it things.

**AICore-exclusive, on purpose.** This app runs only on hardware that can run
Gemini Nano — a Pixel 10 today. A phone that fails the eligibility check is
stopped before it ever reaches the conversation screen, with no degraded
fallback: see `AiCoreAvailability.kt` and CLAUDE.md.

**The phone's camera is a second sense, not a second app.** Point it at
something and the full ML Kit Vision roster — labelling, face and pose
detection, object tracking, barcode and text recognition, segmentation —
looks at the frame, and the pet answers *in character* through the same
speaker, the same way it answers a spoken question. A personal document
scanner rides along for your own use, kept deliberately separate from the
pet's perception. See `android/.../vision/`.

**The pet owns its own life.** Hunger, happiness, life stage and death are
simulated on the board, persisted to its own flash, and timed by its own clock.
The phone contributes screen time, language, Gemini Nano and its camera. If you
never connect a phone again, the pet goes on living — it just goes quiet.

## Hardware

| | |
|---|---|
| Board | Waveshare ESP32-S3 Touch AMOLED 1.8 |
| Display | 1.8-inch 368×448 AMOLED, capacitive touch |
| Orientation | the UI runs rotated — 448 wide, 368 tall |
| Audio | onboard mic and speaker |
| Link | BLE to an Android phone |
| Phone | a Gemini Nano/AICore-capable device — a Pixel 10 as of writing |

Both V1 and V2 board revisions are supported; the panel driver (SH8601 or
CO5300) is detected at runtime.

A camera on the pet itself, and a docked USB-video mode, are planned but not
yet built — see CLAUDE.md's "Pixel 10 as a second brain" section for exactly
what's shipped versus deferred and why.

## The two documents

Both are cited from the source, by section number, so a comment explaining *why*
a thing is the way it is can point at the argument instead of restating it.

**[`DESIGN.md`](DESIGN.md) — product and UX.** The state model both surfaces
render, the flows, and the decisions with their reasoning: why the allowance is
per-sitting rather than per-day (a daily total never falls, so a pet made ill at
11am could not recover until midnight), why the score is called *satiety* rather
than *hunger* (so that `if (hunger > 3)` cannot read correctly and mean the
opposite), and the component rules the UI is built from.

**[`CLAUDE.md`](CLAUDE.md) — how to work on this.** Written as instructions for
[Claude Code](https://claude.com/claude-code), which is what it is named after,
and it doubles as the conventions file: the build commands and the traps in them,
the rule that faces and personas are *generated* and must not be hand-edited,
the AICore migration's hard gate and what the phone's camera does and doesn't do
yet, and the practice that every test here was checked by breaking the code it
covers and confirming it fails. Read it before changing anything, whatever you
are using to change it.

## Repository layout

```
pet-esp32/      ESP-IDF firmware — the display, mic, speaker and the simulation
android/        the app — BLE, Gemini Nano (AICore), platform TTS, ML Kit
                Vision/camera, screen-time tracking
design-system/  design tokens, plus the pet's faces and personas (source of truth)
tools/          generators that turn faces and personas into firmware and app code
shared/         the wire protocol header shared by both sides
hardware/       printable enclosures (STL) — the shells shown in the video
licences/       full texts for the bundled fonts and (historical) the GPL-3.0
DESIGN.md       product and UX decisions, cited from the source by section
CLAUDE.md       build commands, conventions, and the traps worth knowing
```

The firmware and the app share a protocol (`pet-esp32/main/pet_proto.h` and
`android/.../ble/PetProtocol.kt`) and must be changed in step. Both declare a
version constant, and the pet reports its own version when it connects — but
nothing currently compares the two, so a mismatched board and app will pair and
then misbehave. Flash both from the same commit.

## Building

### Firmware

```bash
. ~/esp/esp-idf/export.sh
cd pet-esp32
idf.py build
idf.py -p /dev/cu.usbmodem* flash
```

The display can stay black after a flash until the board is power-cycled — pull
the USB cable and reconnect before concluding anything is broken.

### Android app

The build requires **JDK 21**. Kotlin 2.1 cannot parse a JDK 25 class file, and
a system JDK newer than 21 will fail in a way that does not name the cause.

You also need the Android SDK located, via `ANDROID_HOME` or an `sdk.dir` line
in `android/local.properties` — that file is deliberately not committed, being
specific to your machine.

```bash
cd android
export ANDROID_HOME="$HOME/Library/Android/sdk"
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ./gradlew assembleDebug
```

#### Native dependencies

The app links exactly one native library now: **Opus**, the pet-speaker/BLE
audio codec. It is not in this repository — it is a git clone whose own `.git`
would become a broken gitlink here. What is kept instead is the exact commit it
was built from, in [NOTICE](NOTICE), so a build can be reproduced rather than
approximated:

```bash
tools/fetch-natives.sh
```

Everything else that used to live here — llama.cpp, Whisper.cpp, Piper,
espeak-ng, ONNX Runtime, and the prebuilt `.so`/phoneme-data files they needed —
is gone. The LLM and speech recognition are Gemini Nano via AICore (a Gradle
dependency, downloaded by Google Play services, not vendored source), and
text-to-speech is the Android platform's own `android.speech.tts.TextToSpeech`.

Tests — 472 of them, no device required:

```bash
cd android && ./gradlew testDebugUnitTest
```

Most are pure logic. Five read files rather than calling functions, because
they check things a compiler cannot: WCAG contrast ratios across both colour
schemes, a ban on raw `.dp`/`.sp` literals in UI code, and three that hold the
generated face sets, colour tokens and component roster in sync with
`design-system/`.

## Models

**No model files ship with the app, and none are imported by hand any more.**
The LLM and speech recognition are Gemini Nano, downloaded by Android through
Google Play services on first use, per Google's own eligibility and download
flow — there is no `.gguf`, no Whisper `.bin`, and nothing to pick with a file
browser. Text-to-speech is whatever voice the Android platform's own
`TextToSpeech` engine has installed, also not something this app bundles or
manages.

An earlier version of this app worked the older way — three user-supplied
model files (`.gguf`, a Whisper `.bin`, a Piper `.onnx`) — which is what the
now-removed `ModelSettingsScreen`'s import flow, and the video linked above,
show.

## The pet's face and voice are generated

`design-system/faces/*.json` and `design-system/personas/*.json` are the
sources. The firmware's face tables, the app's face sets, the notification
icons and the persona prompts are all generated from them:

```bash
tools/gen-faces.py
tools/gen-personas.py
```

Both refuse input that breaks a rule and say which one. A happy face must
smile. A dead one has no mouth. Sad and sick must not be confusable. A spiral
eye must actually turn, and must not wind tight enough to fill in. A persona
may not ask you a question or read a list of apps aloud. Those rules are the
design, written where they can fail a build.

## Licence

PolyMO's source is **Apache-2.0**, in full — see [LICENSE](LICENSE).

That is a change worth stating plainly. An earlier version of this app linked
espeak-ng (GPL-3.0-or-later) as part of Piper's text-to-phoneme step, which
meant any *released APK* — the combined binary, not the source tree — had to
be distributed under GPL-3.0 as well. The Gemini Nano migration removed Piper
and espeak-ng from the build entirely: text-to-speech is now the Android
platform's own engine, called through its ordinary SDK API rather than linked
into this app's native code. With espeak-ng gone, nothing in the current build
carries a copyleft obligation, native or otherwise — Opus (the one remaining
native dependency) is BSD-3-Clause, and ML Kit/CameraX/Play Services are
ordinary Google-published Android libraries, not code this app bundles or
statically links.

[NOTICE](NOTICE) has the current dependency list and states this history in
full; `licences/GPL-3.0.txt` stays in the repository as a historical reference
for APKs built from older commits, not because a current build needs it. This
is a description of the licences involved, not legal advice — if you are
building on top of this project's history rather than its current state,
check which commit you actually mean.
