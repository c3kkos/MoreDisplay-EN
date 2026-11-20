#!/usr/bin/env bash
# Builds the cast bench from the app's real stream classes. Usage: build.sh <kotlinc> [out jar]
set -euo pipefail
cd "$(dirname "$0")"
KOTLINC=$1; JAR=${2:-bench.jar}
CAST=../../app/src/main/java/io/github/ynkcc/moredisplay/cast
"$KOTLINC" -nowarn -include-runtime -d "$JAR" Bench.kt stubs/android/util/Log.kt stubs/android/os/SystemClock.kt \
  "$CAST/TsMuxer.kt" "$CAST/HlsSegmenter.kt" "$CAST/HlsServer.kt" "$CAST/AudioTimeline.kt" OldAudioTimeline.kt Sim.kt
