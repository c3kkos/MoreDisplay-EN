# Cast bench

Tests the cast stream on a PC, with no tablet and no TV. It runs the app's **real** stream classes
(`HlsSegmenter`, `TsMuxer`, `HlsServer`, `AudioTimeline`) on the desktop JVM and plays the stream with **Shaka
Player**, the player family of Google's Default Media Receiver, in a browser.

What it can't do: it doesn't run Android's capture (MediaCodec, the virtual display, the audio loop-back) or a
real Cast receiver. Check the final result on a real TV.

## Tools

- **ffmpeg** (the gyan.dev "release essentials" build works) and **kotlinc** (2.4.20 tested), plus a JDK and
  Python 3 for a local web server. Nothing needs the Android SDK.

## Files

| File | What it is |
| --- | --- |
| `make-clip.sh` | Makes the test clip: black video with a white flash and a 1 kHz beep every 2 s (`video.h264`, `audio.pcm`). |
| `Bench.kt` | Plays a clip in real time through `HlsSegmenter` + `HlsServer` on 127.0.0.1 (Smooth settings, start-up burst). Prints the playlist URL. |
| `Sim.kt` | Offline sound-path simulator: sound arrives on a sample clock that is *n* ppm off, 40 ms late, with pump stalls; runs `AudioEncoder`'s feeder loop with the current `AudioTimeline` or the 2.0.0 one (`OldAudioTimeline.kt`) and reports the flash-to-beep offset over time. 30 minutes take seconds. |
| `receiver.html` | Shaka Player 4.12 + mux.js receiver. Measures, on actual playback, the flash-to-beep offset, pauses and the buffer. Results in `window.bench` (`bench.summary()` gives per-minute numbers). |
| `run-tests.sh`, `TestRunner.kt`, `stubs/` | Runs the cast unit tests without Gradle (minimal JUnit and Android stand-ins). |

## Use

```bash
FF=<path>/ffmpeg.exe; KC=<path>/kotlinc/bin/kotlinc.bat; DATA=<work dir outside the repo>
./make-clip.sh "$FF" "$DATA" 30
"$FF" -f s16le -ar 48000 -ac 2 -i "$DATA/audio.pcm" -c:a aac -b:a 128k -f adts "$DATA/audio.aac"
./build.sh "$KC" "$DATA/bench.jar"
./run-tests.sh "$KC" "$DATA"

# Offline: old vs new sound timeline, sound clock 0.02% fast, 30 minutes (optional 5th argument 1 = the app is
# silent for 10 s every 5 minutes; 6th = write the processed sound to a .pcm file)
java -cp "$DATA/bench.jar" castbench.SimKt "$DATA/audio.pcm" old 200 30
java -cp "$DATA/bench.jar" castbench.SimKt "$DATA/audio.pcm" new 200 30

# Live: serve the stream and the receiver page, then open the page and press Start (keep it visible:
# hidden pages don't paint video frames)
java -cp "$DATA/bench.jar" castbench.BenchKt "$DATA/video.h264" "$DATA/audio.aac" 0 10
python -m http.server 8765 --bind 127.0.0.1
#   http://127.0.0.1:8765/receiver.html?src=<playlist URL printed by the bench>
```

`Bench` takes an optional drift in ms per minute that shifts the sound's *timestamps*: a calibration showed that
Shaka ignores them (it plays the samples back to back), so sound and picture stay together only through the
number of samples. That is why `AudioTimeline` corrects the sample count, not the timestamps.
