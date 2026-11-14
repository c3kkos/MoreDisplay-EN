# Changelog

## 1.8.2-EN (versionCode 15, English edition)

**Reboot once after updating** (the sound fix is in Android's system process).

### Fixed
- **No sound on the TV ("failed to register the audio loopback").** On Android 12L, Android's audio service
  refuses a sound-capture registration that comes from its own system process, even though that process has
  the permission. The Vector module now answers that one permission check as "allowed", only while MoreDisplay
  itself is registering the cast sound; every other app's request is checked by Android as before.
- **Stuttering picture.** The picture now goes to the encoder at a perfectly steady frame rate (the 30 or 60 fps
  set in Settings), always with the newest picture and with evenly spaced timestamps. Before, frames went out
  whenever the display drew, at uneven intervals, which TVs show as stutter.

### Added
- **Settings → Cast → Playback**: *Smooth* (default) sends 2-second segments, so the TV keeps more video in
  reserve and rides out Wi-Fi hiccups (about 2 s more delay); *Low delay* keeps 1-second segments as before.

## 1.8.1-EN (versionCode 14, English edition)

### Fixed
- **Cast always failed with `CAST-06` "the device isn't on the network any more".** The device list only stays
  fresh while it's being searched, and MoreDisplay connected to the device only after warming up the stream, when
  the list was already gone. The device is now connected the moment it's picked (the search stops afterwards); the
  stream warms up at the same time, and the device is told to play once both are ready.
- A Cast session ending from an earlier cast (e.g. switching from the Shield to the TV) could stop the new cast:
  only the session started by the current cast counts now.
- The app said "Casting to …" before the device had even connected: it now says "Connecting to …" until the device
  plays the stream, and reports `CAST-06` if the device doesn't accept the connection within 30 s.
- Stop while a cast was still starting didn't cancel the start-up. Every cast attempt is numbered, so late events
  from an older attempt can't stop a newer one, and all stopping runs in one place on the main thread.
- When the TV's player failed, or was stopped from the TV, MoreDisplay kept streaming: it now stops cleanly, or
  reports `CAST-07` with the reason. The Cast session is also ended if Android closes the cast service.
- adb `cast`: connects before stopping the device search.

### Changed
- Audio-only Cast devices (speakers, speaker groups) are no longer listed.
- The live playlist lists 6 s of stream (was 4 s) and keeps 12 s downloadable, so a short Wi-Fi hiccup on the TV
  side doesn't stall playback. The delay stays the same.

## 1.8.0-EN (versionCode 13, English edition)

Maintenance release: no new features and no intended behaviour changes, except the icon.

### Changed
- **New launcher icon** from the repository logo (rainbow TV with "MD") on a warm light background, as an adaptive
  icon with a themed (monochrome) layer for Android 13+.
- **New eye widget look:** a rounded square (stays square at any size), black inside, with a rainbow frame that
  runs three times around the edge: vivid while the eye is on, washed out while it's off. The eye is bigger
  (40dp at 1×1, was 32dp), and on Android 12+ a 2×2 or larger widget switches to a large layout (72dp eye).
- **No main-thread waits on the daemon:** closing the eye and stopping a cast no longer block the screen while
  the daemon answers. Detaching a viewer and stopping cast sound are tied to the caller's token, so a late request
  can never affect a newer viewer or cast.
- Eye, cast and touch requests (including detach and stop) are accepted only from MoreDisplay itself.
- Internal: hidden Android APIs (display list, input injection, task focus, audio policy) in one `HiddenApi`
  helper; one side channel (`RpcExtras`) and one "send to the display's channel" helper instead of three copies;
  the eye's viewer split into picture source, touch control and window; Shizuku interface method renamed
  `sendWithExtras`; the encoder's redundant repeat-frame setting removed. Protocol version 23: reboot after
  updating (the Shizuku service restarts by itself).
- CI runs Android lint (errors fail the build, findings are printed in the log); two intentional uses of APIs that
  are hidden on older Android versions are now marked as such.

## 1.7.1-EN (versionCode 12, English edition)

### Fixed
- **Cast failed with "The video encoder produced no picture".** A virtual display only sends a frame when its
  content changes, and many encoders ignore the "repeat the last frame" setting, so a still screen gave the encoder
  one frame and the stream never got its 1-second segments. The frame hub now keeps a steady frame rate (repeating
  the latest picture, black until the first one), asks the encoder for a key frame every second
  (`PARAMETER_KEY_REQUEST_SYNC_FRAME`) instead of relying on its own interval, and stamps frames with
  `System.nanoTime()`, the same clock as the cast sound.

- **The eye froze when a cast started, and stayed frozen if the cast failed.** The eye now switches to the cast's
  picture as soon as the cast takes the display, and when a cast stops or fails it gets the picture back from the
  daemon (after the cast has released the display, so the hand-back can't detach the eye).
- Live video could also produce no cast picture: frames were stamped with the virtual display's own timestamps,
  and encoders drop frames whose timestamps don't increase. Frames are now stamped with `System.nanoTime()`.

### Added
- **Cast error codes** `CAST-01` … `CAST-10` with details (frames from the display, drawn, encoded, key frames,
  segments, Chromecast requests), selectable in the app and explained in the README. The log prints the counters
  every 10 s while casting, and the cast stops with `CAST-08` if the Chromecast hasn't fetched anything 30 s after
  being told to play.

## 1.7.0-EN (versionCode 11, English edition)

### Added
- **Touch control in the full-screen eye.** Taps, swipes, scrolling and pinch on the viewer's picture are mapped to
  the virtual display's pixels and sent over a pipe (opened once per viewer) to the daemon that owns the display,
  which injects them into that display only (Vector: system_server; Shizuku: shell). A Back button in the viewer's
  controls sends Back to the app on the display. After each gesture, focus is given back to the viewer's task
  (`ActivityTaskManager#setFocusedTask`), so the tablet's own Back (which shrinks the viewer to PiP) and Home keep
  working. PiP itself stays view only (Android keeps touches on PiP windows for its own controls).
- Settings → Eye controls: *Touch control in the full-screen eye* (on) and *Show touches* (off).
- **Orientation: Landscape** policy field: portrait requests of the app (manifest and `setRequestedOrientation`)
  are answered with landscape, via a hook on `WindowContainer#setOrientation`, so a portrait app fills a landscape
  virtual display instead of being letterboxed. Saved across reboots; adb `--es orientation landscape`.
- Unit tests for the touch event encoding, coordinate mapping, orientation mapping and the new policy field.

### Changed
- Internal protocol version 22 (touch channel, policy orientation field): reboot after updating.

## 1.6.0-EN (versionCode 10, English edition)

### Added
- **Cast a virtual display to a Chromecast, 1:1.** The display's frames go (through the same output switch as the
  eye) to the tablet's hardware H.264 encoder at the display's own size, are packaged as a live HLS stream (MPEG-TS,
  1-second segments) and served by a small HTTP server on the tablet's Wi-Fi address under a random per-session
  path. The Chromecast plays it with Google's Default Media Receiver, so no Cast developer registration is needed.
  Delay about 2–5 s.
- **Cast sound (Vector only):** in system_server, a dynamic audio policy (match uid, loop-back only) takes the sound
  of the apps set to "Open on" the cast display off the speaker and pipes it to MoreDisplay, which encodes it as AAC
  in the same stream. Apps that forbid sound capture are respected.
- The eye works while casting: a GPU frame hub copies each frame to the encoder and the eye window.
- Settings → Cast (display, quality, frame rate, sound, keep awake), a Cast card with device picker on the Displays
  tab, "Casting to …" on the display card, a notification with Stop, and `adb ... --es action cast --es state on|off`.
- Unit tests for the MPEG-TS muxer and the HLS segmenter.

### Changed
- Internal protocol version 21 (cast sound requests): reboot after updating.
- New permissions: network access, foreground service (connected device), notifications, wake lock.

## 1.5.1-EN (versionCode 9, English edition)

### Changed
- Display cards show the display's name in the title: "Display #2 · TV".
- Version names now always have three numbers (1.5.1-EN), like the release tags.

## 1.5-EN (versionCode 8, English edition)

### Added
- **Open on**: a policy can name the display an app always opens on, whoever starts it (home-screen icon,
  notification, another app, the app itself). Pick a virtual display to keep the app entirely off the built-in
  screen and watch it with the eye. Until now a policy only controlled which displays an app could *see*, so an app
  launched from the home screen still opened on the built-in screen. Implemented in system_server by setting the
  launch display on the start request (`ActivityStarter#executeRequest`); an existing task is moved to that display
  when it's brought to front. Saved and restored across reboots like the other policy fields. adb:
  `--ei launch_display_id N`. The policy editor warns if the app couldn't see its Open-on display.

### Fixed
- The launch permission hook (needed to start apps on managed displays) is now also installed on Android 10/11.

### Changed
- Internal protocol version 20 (the policy record gained a field): reboot after updating, as always.

## 1.4.2-EN (versionCode 7, English edition)

### Changed
- **Easy to tell apart from the original in Vector's module list:** the app is now called **MoreDisplay EN**, the
  version reads `1.4.2-EN`, and the description starts with "English edition by c3kkos, based on MoreDisplay by
  ynkcc". (Vector shows the app name, version and description there.)
- The create-display dialog notes that Android picks the display number and may skip numbers.

## 1.4.1 (versionCode 6, English edition)

### Fixed
- **Android 10-13: saving a policy with a recording redirect failed with "display ... does not exist"**, even for a
  display MoreDisplay had just created. The display-list query used `getDisplayIds(boolean)`, which only exists on
  Android 14+, so on older versions it found no displays at all. Both forms are now supported.
- **Android 10-13: whitelist/blacklist didn't apply to the display list apps get.** The hook on the system's
  `getDisplayIds` had the same Android 14+ signature, so it wasn't installed on older versions (only the by-id check
  was). It now installs on both.
- **Android 12/12L: recording redirect wasn't installed.** On these versions `createVirtualDisplayInternal` takes its
  arguments in a different order (the display config comes last). Arguments are now found by type.
- Android 12/13: the display-event filter returned a value from a `void` method; it now returns correctly on both
  forms.

### Added
- The Displays page lists **Other displays** (not managed by MoreDisplay) and explains display numbering: Android
  hands out the numbers itself and never reuses one until a restart, so a new display can skip a number.

## 1.4 (versionCode 5, English edition)

### Added
- **The eye: a picture-in-picture viewer for virtual displays (view only).** Shows a managed virtual display live
  in a real Android PiP window on the built-in screen, which can be expanded to full screen and shrunk back at will.
  Toggling the eye only redirects the display's output (`VirtualDisplay#setSurface`) between its internal sink and
  the viewer: the display is never recreated, resized or paused, so the apps on it keep running untouched. The
  viewer asks for frames at the display's full resolution. If the viewer dies, the daemon puts the sink back on its
  own (binder death watch), so a crashed viewer can never freeze a display.
- Four eye controls, each with its own switch in the new **Settings** tab: an eye button on every display card, a
  Quick Settings tile, a home-screen widget and `adb ... --es action eye [--ei display_id N] [--es state on|off|toggle]`.
  The tile and widget act on the last watched display, remembered across reboots.
- On the Vector channel, apps on a watched display don't get the "display changed" events caused by switching the
  eye on or off (a 1.5 s window per switch; added/removed events and system processes are never affected).

### Changed
- The Policies tab now uses a lock icon; the gear icon belongs to Settings.
- In system_server, attaching a viewer is only accepted from the MoreDisplay app itself.

## 1.3 (versionCode 4, English edition)

### Fixed
- **Displays and policies were lost on every reboot.** They only lived in the system's memory. Every change made
  from the app or from adb is now saved, and after a reboot MoreDisplay recreates the saved displays and re-applies
  the policies automatically. Because recreated displays get new ids, policies are saved against the display
  itself and re-linked to its new id. The same restore runs whenever the app connects, without creating duplicates.
  On the first start of 1.3, the displays and policies that already exist are adopted and saved.

### Changed
- The Displays page labels the Xposed channel **MoreDisplay** instead of "LSPosed".

## 1.2 (versionCode 3, English edition)

### Fixed
- **Creating a display failed on Android 10, 12, 12L and 13** with "Wrong number of arguments; expected 6, got 5".
  The Xposed engine called the hidden `DisplayManagerGlobal#createVirtualDisplay` with a fixed 5 arguments, which
  only matches Android 11 and 14+. Arguments are now built from the method's actual parameter list on every
  version, and Android 10 uses its older `createVirtualDisplay(name, width, height, ...)` form.

### Added
- **The device's built-in screen (display #0) is listed as the default managed display**: first on the Displays
  page (with its real resolution, density, name and refresh rate), as a locked always-visible chip in the policy
  editor, and first in `adb ... --es action list`. It can't be removed or hidden from apps.

## Unreleased (English / Vector fork)

### Changed
- **English only.** Every Chinese UI string, log message, comment, build script note and doc line
  has been translated to English; the source tree contains no CJK characters anymore.
- **Vector compatibility.** The module already targets libxposed API 102, which is exactly the
  API revision pinned by [Vector](https://github.com/JingMatrix/Vector) master. On top of that:
  - Added `android:description` to the manifest. Vector's manager reads a modern module's
    description from this attribute, so it was previously shown blank.
  - Comments, UI hints and docs now refer to the correct libxposed scope (`system`, i.e. system
    server, matching `META-INF/xposed/scope.list`) instead of `android`, and to Vector rather than
    LSPosed 1.9.x.

## 1.1 (versionCode 2) — 2026-10-10

### Fixed
- **Shell-created displays stayed `state=OFF`.** `ShellDisplayEngine` created virtual displays
  with a null `Surface`, so SurfaceFlinger never composited them: apps on those displays got no
  vsync and never drew, and capturing produced nothing. A discard output sink is now attached on
  creation (same approach as `SystemDisplayEngine`), closed on removal and on failed attempts.
  `DaemonProtocol.DAEMON_VERSION` bumped 16 → 17 because the daemon survives app reinstalls and
  the fix would otherwise never reach a device with a running daemon.
- **Target package lookup failed on Android 11+.** Package visibility made
  `PackageManager.getApplicationInfo(other package)` throw `NameNotFoundException`, so the
  `target_package` argument and the policy UI always reported "package not installed".
  The app now declares `QUERY_ALL_PACKAGES`.

### Changed
- Release builds enable R8 and drop unused dependencies (`material`, `appcompat`): release APK
  shrinks 25.8 MB → ~2.3 MB. The theme now inherits
  `@android:style/Theme.Material(.Light).NoActionBar`; `ui-tooling-preview` is debug-only.

### Probe
- New `Device info` button and `--es task info` dump every display device from `dumpsys display`:
  displayId, name, size, dpi, type, state/committedState, touch, owner uid, mirror source and
  FLAG_* bits. `state` is the key diagnostic — a virtual display without an output Surface
  reports OFF.

## 1.0 (versionCode 1) — 2026-10-09

### Added
- Initial public release.
- system_server-side virtual display manager: create / list / remove managed displays.
- Per-uid display visibility filtering covering the list API, the by-id lookup API and
  display add/remove events.
- MediaProjection recording source redirection to a managed display.
- Accessibility gesture redirection to a managed display.
- Launch-on-display bypass so apps can start themselves on managed displays.
