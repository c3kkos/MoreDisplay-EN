# MoreDisplay (English edition)

<p align="center">
  <img src=".github/logo.svg" alt="MoreDisplay logo: a rainbow TV screen with the letters MD" width="220">
</p>

> **All credit goes to [ynkcc](https://github.com/ynkcc), the original author of MoreDisplay.**
> Original project: **https://github.com/ynkcc/MoreDisplay**
> The design, the hooks and every feature are his work. This fork only translates the app and the
> code to English and adds notes for the [Vector](https://github.com/JingMatrix/Vector) framework.
> If you like it, please star and support the original project.

---

## What is MoreDisplay?

MoreDisplay is an Android module that lets you **create extra "virtual screens" inside your phone**
and decide **which app can see which screen**.

Think of a virtual screen as a second, invisible monitor plugged into your phone. Apps can be
launched onto it and keep running there, without showing up on your real screen.

With MoreDisplay you can:

| You want to... | MoreDisplay feature |
| --- | --- |
| Run an app on a hidden extra screen | **Managed displays**: create, list and remove virtual screens |
| Stop an app from noticing the extra screens | **Per-app visibility**: hide screens from an app (blacklist) or show it only some screens (whitelist) |
| Make an app's screen recording capture a virtual screen instead of your real one | **Recording redirect** |
| Make an accessibility app (auto-clicker, macro tool) tap on a virtual screen instead of your real one | **Accessibility redirect** |
| Keep apps on virtual screens out of your Recents list | **Hide from recents** (automatic) |

Everything happens inside the Android system process. MoreDisplay never touches or modifies the
target apps themselves.

---

## Requirements

- **Android 10 or newer** (developed and tested on Android 16)
- **Root** (Magisk, KernelSU or APatch) with **Zygisk** working
- **[Vector](https://github.com/JingMatrix/Vector)**, the modern successor of LSPosed.
  Any framework that supports **libxposed API 102** should work.

No root? See [Shizuku mode](#no-root-shizuku-mode) below; it has fewer features.

---

## Download

### ➜ [Download the latest release](../../releases/latest)

On the release page, under **Assets**, download **`MoreDisplay-<version>.apk`**.
That is the app (the Vector module itself). No GitHub account needed.

(`MoreDisplayProbe-<version>.apk` is a small test app for developers. You can ignore it.)

> **Coming from the original MoreDisplay?** This build is signed with a different key, so uninstall
> the original app first, then install this one.

<details>
<summary>Want the newest development build instead?</summary>

Every change also produces a test build in the **[Actions tab](../../actions/workflows/ci.yml)**:
open the latest run with a green check mark, scroll to **Artifacts** and download
**`moredisplay-debug`** (GitHub login required, kept for 90 days). Inside, `app-debug.apk` is the app.
</details>

---

## Installation (Vector, recommended)

1. Install **Vector** and reboot.
2. Install the **`MoreDisplay-<version>.apk`** you downloaded.
3. Open the **Vector manager**, go to **Modules** and enable **MoreDisplay**.
4. In the module's scope, tick **System Framework** only. Nothing else is needed.
5. **Reboot** your phone.
6. Open **MoreDisplay**. The **Home** tab should show the
   **Xposed / Vector (system_server)** channel as connected.

---

## How to use the app

The app has three tabs at the bottom.

### 1. Home

Shows how MoreDisplay is connected to your system.

- **Connect** / **Disconnect** / **Refresh**: manage the connection.
- Each connected channel lists its features with a ✓ or ✗
  (for example *Per-uid visibility* or *Recording redirect*).

If it says *"No access method detected"*, check that the module is enabled in Vector with the
**System Framework** scope and that you rebooted.

### 2. Displays

Your screens. The **Built-in** section always comes first: it shows your device's own screen
(display `#0`, e.g. your tablet's panel) with its resolution and density. It is the default managed
display, so it can't be removed.

To add a virtual screen:

1. Tap **+** next to a channel.
2. Fill in **Width**, **Height** and **Density (dpi)**, for example `1080` × `1920` @ `320`.
3. Optionally give it a **Name**. Keep the pre-filled **flags** value if unsure.
4. Tap **Create**. The new screen appears in the list with its **display ID** (for example `#2`).
   You will need this number later.

Your last two sizes are remembered so you can reuse them with one tap.
Tap the trash icon to remove a screen.

Virtual screens created through Vector are listed under **MoreDisplay**, the ones created through
Shizuku under **Shizuku**.

### 3. Policies

Rules that decide what a specific app can see or where it acts.

1. Tap **+** (*Add policy*).
2. **Target app**: tap to pick an app from the list (or type its uid).
3. **Visibility**:
   - **All**: no restriction (default)
   - **Whitelist only**: the app sees only the screens you tick
   - **Blacklist**: the app cannot see the screens you tick

   The built-in screen (`#0`) is shown as a locked chip: it is always visible to every app.
4. **Recording redirect** (optional): pick a virtual screen (`#0 Built-in` means no redirect). When this app records the screen,
   it will record that virtual screen instead.
5. **Accessibility redirect** (optional): pick a virtual screen. Taps and swipes performed by this
   app's accessibility service will land on that virtual screen.
6. Tap **Save**. Rules take effect immediately, no reboot needed.

### Your setup survives reboots

Every virtual screen and rule you create (in the app or with adb) is saved. After a reboot,
MoreDisplay recreates your virtual screens and re-applies your rules on its own, a few seconds
after the phone finishes starting. The recreated screens may get new numbers (`#3` may become `#4`);
your rules follow them automatically.

> Some phones (Xiaomi/HyperOS, OPPO/ColorOS, vivo, Huawei and others) stop apps from starting at boot.
> If your setup doesn't come back after a reboot, allow **Autostart** for MoreDisplay in the system
> settings, or just open the app once: it restores everything when it connects.

> Your real main screen (display `0`) can never be hidden from an app; that would crash it.

---

## No root? Shizuku mode

MoreDisplay can also run through [Shizuku](https://shizuku.rikka.app/) without root or Vector,
with a reduced feature set:

| Feature | Vector / Xposed | Shizuku |
| --- | :---: | :---: |
| Create / remove virtual screens | ✓ | ✓ |
| Launch apps onto virtual screens | ✓ | ✗ |
| Always-unlocked virtual screens | ✓ | ✗ |
| Per-app visibility (whitelist / blacklist) | ✓ | ✗ |
| Recording redirect | ✓ | ✗ |
| Accessibility redirect | ✓ | ✗ |
| Hide from recents | ✓ | ✗ |

Start Shizuku, open MoreDisplay, tap **Connect** on the Home tab and allow the permission.
Both channels can be connected at the same time.

---

## Advanced: control it with adb

Every action is also available from a computer with `adb`. The result appears as a toast on the
phone and in `adb logcat -s MoreDisplay_Cmd`.

```sh
# Create a 1280x720 virtual screen at 240 dpi (flags=1 means PUBLIC)
adb shell am start -n io.github.ynkcc.moredisplay/.CommandActivity \
  --es action create --ei width 1280 --ei height 720 --ei dpi 240 --ei flags 1

# List screens (the built-in screen comes first) / remove a virtual screen
adb shell am start -n io.github.ynkcc.moredisplay/.CommandActivity --es action list
adb shell am start -n io.github.ynkcc.moredisplay/.CommandActivity --es action remove --ei display_id 2

# Hide screen 2 from an app (by package name)
adb shell am start -n io.github.ynkcc.moredisplay/.CommandActivity \
  --es action policy-set --es target_package com.example.app --es mode hide --eia display_ids 2

# Make an app's screen recordings capture screen 2
adb shell am start -n io.github.ynkcc.moredisplay/.CommandActivity \
  --es action policy-set --es target_package com.example.app --es mode all --ei record_display_id 2

# Show all rules / delete a rule / check what an app can see
adb shell am start -n io.github.ynkcc.moredisplay/.CommandActivity --es action policy-list
adb shell am start -n io.github.ynkcc.moredisplay/.CommandActivity \
  --es action policy-remove --es target_package com.example.app
adb shell am start -n io.github.ynkcc.moredisplay/.CommandActivity \
  --es action displays-for-uid --es target_package com.example.app
```

| Action | What it does | Main options |
| --- | --- | --- |
| `create` | New virtual screen | `width`, `height`, `dpi`, `name`, `flags`, `privilege` (`lsposed` or `shell_shizuku`) |
| `list` | List virtual screens | |
| `remove` | Delete a virtual screen | `display_id` |
| `hold` | Check that a virtual screen is still managed and alive | `display_id` |
| `policy-set` | Add or replace a rule | `target_package` or `uid`, `mode` (`all` / `only` / `hide`), `display_ids`, `record_display_id`, `operated_display_id` |
| `policy-list` | Show all rules | |
| `policy-remove` | Delete a rule | `target_package` or `uid` |
| `displays-for-uid` | Which screens an app can see | `target_package` or `uid` |
| `shizuku-permit` | Ask for the Shizuku permission | |

Common `flags` values (add them together): `1` PUBLIC, `64` SUPPORTS_TOUCH,
`1024` TRUSTED (usually Vector only), `4096` ALWAYS_UNLOCKED (Vector only), `16384` OWN_FOCUS.

---

## Troubleshooting

| Problem | What to check |
| --- | --- |
| Home says *"No access method detected"* | Module enabled in Vector? Scope set to **System Framework**? Rebooted? |
| *"package not installed"* when adding a rule | Use the app picker or the exact package name. On multi-user phones use the uid. |
| An app still sees a hidden screen | Close and reopen that app; it may have cached the screen list. |
| Recording redirect does nothing | Make sure the target virtual screen exists and has something running on it. |
| Install fails over an older version | Uninstall the original MoreDisplay first (different signature). |
| Setup not back after a reboot | Allow Autostart for MoreDisplay, or open the app and tap Connect. Displays made through Shizuku come back once Shizuku is running. |

---

## Build from source

```sh
git clone https://github.com/c3kkos/MoreDisplay-EN.git
cd MoreDisplay-EN
./gradlew :app:assembleDebug        # output: app/build/outputs/apk/debug/
```

Requires JDK 17 or newer and the Android SDK. Technical details are in
[dist/README.md](dist/README.md), and changes in [CHANGELOG.md](CHANGELOG.md).

---

## Credits

- **[ynkcc](https://github.com/ynkcc)**: author of
  [MoreDisplay](https://github.com/ynkcc/MoreDisplay). Everything in this repository is his work,
  except the English translation and the Vector notes.
- [libxposed](https://github.com/libxposed) and [Vector](https://github.com/JingMatrix/Vector):
  the modern Xposed API and framework.
- [Shizuku](https://github.com/RikkaApps/Shizuku): the no-root channel.

The original project does not include a license file, so all rights remain with the original author.
