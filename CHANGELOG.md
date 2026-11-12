# Changelog

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
