#!/usr/bin/env bash
# Makes the cast-bench test clip: black video with a white flash (2 frames) and a 50 ms 1 kHz beep every 2 s,
# so the offset between picture and sound can be measured anywhere along the chain.
#   video.h264  640x360, 30 fps, H.264 Annex-B, key frame every 30 frames, no B-frames, AUD before each frame
#   audio.pcm   48 kHz stereo s16le
# Usage: make-clip.sh <ffmpeg.exe> <out dir> [minutes]
set -euo pipefail
FFMPEG=$1; OUT=$2; MIN=${3:-30}; SEC=$((MIN * 60))
mkdir -p "$OUT"
"$FFMPEG" -hide_banner -loglevel error -y -f lavfi -i "color=c=black:s=640x360:r=30:d=$SEC" \
  -vf "drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='lt(mod(n\,60)\,2)'" \
  -c:v libx264 -preset veryfast -bf 0 -g 30 -keyint_min 30 -sc_threshold 0 -x264-params aud=1 \
  -pix_fmt yuv420p -f h264 "$OUT/video.h264"
"$FFMPEG" -hide_banner -loglevel error -y -f lavfi \
  -i "aevalsrc=exprs='if(lt(mod(t\,2)\,0.05)\,0.8*sin(2*PI*1000*t)\,0)|if(lt(mod(t\,2)\,0.05)\,0.8*sin(2*PI*1000*t)\,0)':s=48000:d=$SEC" \
  -f s16le -ac 2 -ar 48000 "$OUT/audio.pcm"
ls -l "$OUT"
