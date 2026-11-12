# MoreDisplay (English / Vector edition)

> **All credit goes to [ynkcc](https://github.com/ynkcc), the original author of MoreDisplay.**
> Original project: **https://github.com/ynkcc/MoreDisplay**. The design, the hooks, the daemon
> engine and every feature are his work. This fork only translates the project to English and
> adds small Vector-related touches. Please star, report bugs to, and support the upstream project.

An English-only fork of [ynkcc/MoreDisplay](https://github.com/ynkcc/MoreDisplay), prepared for the
[Vector](https://github.com/JingMatrix/Vector) Xposed framework (the maintained successor of LSPosed).

MoreDisplay runs only inside `system_server`. It manages virtual displays from the system side and lets
you decide, per app, which displays the app can see, which display its screen recordings capture, and
which display its accessibility gestures land on. See [dist/README.md](dist/README.md) for features,
adb commands and limitations, and [CHANGELOG.md](CHANGELOG.md) for what changed in this fork.

## Framework compatibility

| Item | Value |
| --- | --- |
| Xposed API | libxposed API 102 (`minApiVersion=101`, `targetApiVersion=102`) |
| Entry | `META-INF/xposed/java_init.list` -> `io.github.ynkcc.moredisplay.xposed.LsposedModule` |
| Scope | `system` (System Framework / system_server) only |
| Legacy XposedBridge API | not used (Vector refuses legacy calls from API >= 102 modules) |
| Tested framework | Vector v2.x (libxposed API 102) |

## Install

1. Root with Magisk or KernelSU/APatch and make sure Zygisk is running (NeoZygisk/ReZygisk on KernelSU/APatch).
2. Install Vector and reboot.
3. Install the MoreDisplay APK, enable it in the Vector manager and tick **System Framework** in its scope.
4. Reboot. Open MoreDisplay; the Home page should list the Xposed / Vector channel as connected.

Shizuku mode (no Xposed framework) still works with a reduced feature set.

## Build

```sh
./gradlew :app:assembleRelease          # unsigned unless RELEASE_KEYSTORE_* is set
./gradlew :app:assembleDebug
```

Requires JDK 17+ and the Android SDK (compileSdk 37). The GitHub Actions workflow in `.github/workflows/ci.yml`
builds both variants on every push.

Note: the package name is unchanged (`io.github.ynkcc.moredisplay`). An APK you sign yourself cannot be
installed over the original release; uninstall the original first.

## Credits

- **[ynkcc](https://github.com/ynkcc)**: author of [MoreDisplay](https://github.com/ynkcc/MoreDisplay).
  Everything in this repository is his work, except the English translation and the Vector notes.
- [libxposed](https://github.com/libxposed) and [Vector](https://github.com/JingMatrix/Vector): the modern Xposed API and framework.
- [Shizuku](https://github.com/RikkaApps/Shizuku): the non-root channel.
