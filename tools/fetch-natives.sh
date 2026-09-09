#!/bin/sh
# Fetch the native sources the Android build links against.
#
# Down to one dependency since the AICore migration: llama.cpp, whisper.cpp,
# piper, espeak-ng and onnxruntime are gone from the build along with the
# GGUF/Whisper-.bin/Piper-.onnx pipelines they backed (see
# android/app/src/main/cpp/CMakeLists.txt and CLAUDE.md). Opus stays — it is
# the pet-speaker/BLE audio codec, unrelated to which engine produces or
# consumes the PCM either side of it.
#
# It is NOT in this repository on purpose: it is a git clone whose own .git
# would become a broken gitlink here. What this repo keeps instead is the
# exact commit it was built from, so a build can be reproduced rather than
# approximated.
set -eu
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

pin() {  # pin <dir> <url> <commit>
  if [ -d "$1/.git" ]; then
    echo "  $1 already present - leaving it alone"
    return
  fi
  echo "  fetching $1 @ $3"
  git clone --quiet "$2" "$ROOT/$1"
  git -C "$ROOT/$1" checkout --quiet "$3"
}

echo "Pinned sources:"
pin deps/opus https://github.com/xiph/opus.git 3da9f7a6db1c

echo
echo "Done. No .so files or headers need fetching separately anymore -- Opus"
echo "builds from source and everything else the old pipeline needed"
echo "(onnxruntime, espeak-ng) left with it."
