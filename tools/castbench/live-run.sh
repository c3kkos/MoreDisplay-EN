#!/usr/bin/env bash
# One unattended live run: stream <video> + <audio> with the bench, play it in headless Edge with
# receiver.html (server.py must be running on port 8765), report the per-minute results.
# Usage: live-run.sh <bench.jar> <video.h264> <audio.aac> <minutes> <run name> <reports dir>
set -euo pipefail
JAR=$1; VIDEO=$2; AUDIO=$3; MIN=$4; NAME=$5; REPORTS=$6
EDGE="/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe"
PROFILE="$(cygpath -w "$REPORTS")\edge-bench-profile"
LOG="$REPORTS/$NAME.bench.log"
mkdir -p "$REPORTS"
java -cp "$JAR" castbench.BenchKt "$VIDEO" "$AUDIO" 0 "$MIN" > "$LOG" 2>&1 &
BENCH=$!
for i in $(seq 1 50); do grep -aq PLAYLIST "$LOG" && break; sleep 0.2; done
URL=$(grep -a PLAYLIST "$LOG" | awk '{print $2}')
# Like the app: tell the player to play once 6 segments are ready (3 listed, 3 held for the burst).
for i in $(seq 1 100); do curl -s "$URL" | grep -q "seg2.ts" && break; sleep 0.5; done
"$EDGE" --headless=new --autoplay-policy=no-user-gesture-required --no-first-run --disable-extensions \
  --user-data-dir="$PROFILE" "http://127.0.0.1:8765/receiver.html?src=$URL&auto=1&report=$NAME" > /dev/null 2>&1 &
wait $BENCH || true
# Close only the Edge started above (it uses its own profile folder).
powershell -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='msedge.exe'\" | Where-Object { \$_.CommandLine -like '*edge-bench-profile*' } | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }"
py -3 -I -c "
import json, sys
r = json.load(open(sys.argv[1]))
print('$NAME: flashes', r['flashes'], 'beeps', r['beeps'], 'decoded frames', r['decodedFrames'], 'media time', round(r['mediaTime']))
for s in r['summary']:
    if s['pairs']: print('  minute', s['minute'], 'pairs', s['pairs'], 'median', s['medianOffsetMs'], 'ms  range', s['minOff'], '..', s['maxOff'], ' pauses', s['pauses'])
print('  pauses at (s):', [round(e['t']/1000) for e in r['events'] if e['type']=='pause'])
" "$REPORTS/$NAME.json"
