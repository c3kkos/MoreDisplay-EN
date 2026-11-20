#!/usr/bin/env bash
# Runs the app's pure-Kotlin cast unit tests without Gradle. Usage: run-tests.sh <kotlinc> <work dir>
set -euo pipefail
cd "$(dirname "$0")"
KOTLINC=$1; OUT=$2
CAST=../../app/src/main/java/io/github/ynkcc/moredisplay/cast
TEST=../../app/src/test/java/io/github/ynkcc/moredisplay/cast
"$KOTLINC" -nowarn -include-runtime -d "$OUT/tests.jar" TestRunner.kt stubs/org/junit/Junit.kt \
  "$CAST/AudioTimeline.kt" "$CAST/HlsSegmenter.kt" "$CAST/TsMuxer.kt" "$CAST/StallGuard.kt" \
  "$TEST/AudioTimelineTest.kt" "$TEST/StallGuardTest.kt" "$TEST/TsMuxerTest.kt" 2>&1 | grep -v "^warning" || true
java -cp "$OUT/tests.jar" castbench.TestRunnerKt io.github.ynkcc.moredisplay.cast.AudioTimelineTest \
  io.github.ynkcc.moredisplay.cast.StallGuardTest io.github.ynkcc.moredisplay.cast.TsMuxerTest
