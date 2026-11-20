package castbench

import io.github.ynkcc.moredisplay.cast.AudioTimeline
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

/**
 * Offline sound-path simulator (fast, no real time): the app's sound arrives from system_server on a sample clock
 * that is [ppm] off the system clock, 40 ms late, in 20 ms pieces, with the pump stalling for 60-150 ms every few
 * seconds and then bursting. It runs the feeder loop of AudioEncoder with the old or the new AudioTimeline and
 * reports, for each beep of the test clip, how far the sound would play from its flash on a Cast player (players
 * play the samples back to back; the picture follows the system clock).
 *
 * Usage: Sim <audio.pcm> <old|new> <ppm> <minutes> [silent: 1 = the app is silent for 10 s every 5 min] [out.pcm]
 */
fun main(args: Array<String>) {
    val pcm = RandomAccessFile(File(args[0]), "r")
    val mode = args[1]
    val ppm = args[2].toDouble()
    val minutes = args[3].toDouble()
    val silentApps = args.getOrNull(4) == "1"
    val outFile = args.getOrNull(5)?.let { File(it).outputStream().buffered() }
    val rate = 48_000
    val bpf = 4
    val chunkFrames = 960
    val chunk = chunkFrames * bpf
    val clock = 1.0 + ppm / 1e6 // source samples per system-clock sample
    val latencyUs = 40_000L
    val rnd = Random(42)

    val newTl = AudioTimeline(0, rate, bpf)
    val oldTl = OldAudioTimeline(0, rate, bpf)
    val nextUs = { if (mode == "new") newTl.nextUs else oldTl.nextUs }

    // Output position (frames) of each beep onset and content time of each flash.
    var outFrames = 0L
    var inBeep = false
    var quiet = 0
    val offsets = ArrayList<Pair<Double, Double>>() // (minute, offset ms)
    var beepIndex = 0

    fun emit(bytes: ByteArray, len: Int) {
        outFile?.write(bytes, 0, len)
        var i = 0
        while (i + 3 < len) {
            val s = (bytes[i].toInt() and 0xff) or (bytes[i + 1].toInt() shl 8)
            val loud = kotlin.math.abs(s.toShort().toInt()) > 6_000
            if (loud && !inBeep) {
                // The beep for flash k starts at content time 2k s; its flash shows at 2k/clock s on the system
                // clock (the app keeps its picture with its sound clock); the sound plays at its output position.
                val playedS = (outFrames + i / bpf).toDouble() / rate
                val k = Math.round(playedS * clock / 2.0) // the nearest flash (beeps are 2 s apart, offsets well under 1 s)
                val flashS = 2.0 * k / clock
                offsets += (flashS / 60) to (playedS - flashS) * 1_000
                beepIndex++
                inBeep = true
                quiet = 0
            } else if (loud) {
                quiet = 0
            } else if (inBeep && ++quiet > rate / 5) {
                inBeep = false
            }
            i += bpf
        }
        outFrames += len / bpf
    }

    val endUs = (minutes * 60_000_000).toLong()
    var produced = 0L // source frames handed to the pipe
    var pipe = 0L // bytes waiting in the pipe
    var stallUntil = 0L
    var nextStall = 3_000_000L
    val buf = ByteArray(chunk)
    val silence = ByteArray(chunk)
    var now = 0L
    while (now < endUs) {
        // The pump: one 20 ms piece each time its source frames have been captured (plus the capture delay).
        if (now >= nextStall) { stallUntil = now + 60_000 + rnd.nextLong(90_000); nextStall = now + 2_000_000 + rnd.nextLong(3_000_000) }
        if (now >= stallUntil) {
            while (((produced + chunkFrames) / (rate * clock) * 1e6).toLong() + latencyUs <= now) {
                produced += chunkFrames
                val contentS = produced.toDouble() / rate
                if (silentApps && contentS % 300.0 >= 290.0) { pcm.seek(pcm.filePointer + chunk); continue } // silent app: nothing captured
                pipe += chunk
            }
        }
        // The feeder loop (AudioEncoder.feedLoop): read whatever is there, else add silence or wait 5 ms.
        if (pipe > 0) {
            val n = minOf(pipe, chunk.toLong()).toInt()
            pipe -= n
            if (pcm.read(buf, 0, n) < n) break
            if (mode == "new") {
                val out = newTl.take(buf, 0, n, now)
                if (out.isNotEmpty()) { emit(out, out.size); newTl.advance(out.size) }
            } else {
                val keep = oldTl.admit(n, now)
                if (keep > 0) { emit(buf.copyOfRange(n - keep, n), keep); oldTl.advance(keep) }
            }
            continue
        }
        val gap = if (mode == "new") newTl.silenceNeeded(now, chunk) else oldTl.silenceNeeded(now, chunk)
        if (gap > 0) {
            emit(silence, gap)
            if (mode == "new") newTl.advance(gap, silence = true) else oldTl.advance(gap, silence = true)
        } else {
            now += 5_000
        }
    }
    outFile?.close()
    val first = offsets.firstOrNull()?.second ?: 0.0
    println("mode=$mode ppm=$ppm minutes=$minutes beeps=${offsets.size} | " + (if (mode == "new")
        "clock ${newTl.clockMs} ms, dropped ${newTl.droppedMs} ms, silence ${newTl.silenceMs} ms, reference ${newTl.referenceUs?.div(1000)} ms"
    else "dropped ${oldTl.droppedMs} ms, silence ${oldTl.silenceMs} ms"))
    // Offset relative to the first beep, per 5 minutes: worst and last.
    val step = 5.0
    var m = 0.0
    val line = StringBuilder("  offset vs start (ms), per ${step.toInt()} min [min..max]:")
    var worst = 0.0
    while (m < minutes) {
        val w = offsets.filter { it.first >= m && it.first < m + step }.map { it.second - first }
        if (w.isNotEmpty()) line.append(" ${w.min().toInt()}..${w.max().toInt()}")
        w.forEach { if (kotlin.math.abs(it) > kotlin.math.abs(worst)) worst = it }
        m += step
    }
    println(line)
    println("  worst ${worst.toInt()} ms")
}
