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
| Run an app on a hidden extra screen | **Managed displays** + **Open on**: the app always opens on the virtual screen, never on yours |
| Stop an app from noticing the extra screens | **Per-app visibility**: hide screens from an app (blacklist) or show it only some screens (whitelist) |
| Make an app's screen recording capture a virtual screen instead of your real one | **Recording redirect** |
| Make an accessibility app (auto-clicker, macro tool) tap on a virtual screen instead of your real one | **Accessibility redirect** |
| Keep apps on virtual screens out of your Recents list | **Hide from recents** (automatic) |
| Peek at what's running on a hidden screen, and use it | **The eye**: a picture-in-picture viewer; touch control when expanded |
| Make a portrait-only app fill a landscape screen | **Orientation: Landscape** in the app's policy |
| Show a hidden screen on your TV, 1:1 | **Cast**: sends a virtual screen to a Chromecast at its own resolution |

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
3. Open the **Vector manager**, go to **Modules** and enable **MoreDisplay EN**. It shows its version
   (for example `1.4.2-EN`) and a description starting with *English edition*, so it can't be confused
   with the original MoreDisplay.
4. In the module's scope, tick **System Framework** only. Nothing else is needed.
5. **Reboot** your phone.
6. Open **MoreDisplay EN**. The **Home** tab should show the
   **Xposed / Vector (system_server)** channel as connected.

---

## How to use the app

The app has four tabs at the bottom.

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
Tap the trash icon to remove a screen. Tap the **eye** icon to watch it (see below).

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
4. **Open on** (optional): the screen this app always opens on, however it's started (home screen
   icon, notification, another app). Pick a virtual screen to keep the app off your real screen, then
   watch it with the eye. If the app was already open, close it from Recents once.
   Visibility alone does **not** move an app: it only decides which screens the app can *see*.
5. **Orientation** (optional): **Landscape** makes an app that asks for portrait get landscape
   instead, so it fills a landscape virtual screen (e.g. 1280×720) instead of showing as a narrow
   portrait strip with black bars. A virtual screen never rotates, and your tablet's rotation lock
   doesn't affect it. Applies to the app wherever it's shown; an app made only for portrait may look
   stretched.
6. **Recording redirect** (optional): pick a virtual screen (`#0 Built-in` means no redirect). When this app records the screen,
   it will record that virtual screen instead.
7. **Accessibility redirect** (optional): pick a virtual screen. Taps and swipes performed by this
   app's accessibility service will land on that virtual screen.
8. Tap **Save**. Rules take effect immediately, no reboot needed.

**Example: hide an app on screen #2 and watch it.** Add a policy for the app with **Whitelist only**
→ tick `#2`, and **Open on** → `#2`. Launch the app from your home screen as usual: it opens on #2
and nothing appears on your screen. Tap the eye to watch it.

### 4. Settings

Turn each of the four eye controls on or off (see *The eye* below; all are on by default), and set up
casting (see *Cast a virtual screen to a Chromecast* below).

### The eye: watch a hidden screen

The eye shows a virtual screen live in a **picture-in-picture** window on your real screen, so you
can see what a hidden app is doing without moving it. Expand it to full screen to **control the app
by touch**.

- Turning the eye on or off **never touches the virtual screen**: it isn't recreated, resized or
  paused, and apps on it keep running exactly as before. Only the screen's picture is sent to the
  window instead of being thrown away.
- The window can be dragged, resized, **expanded to full screen** and shrunk back to
  picture-in-picture whenever you like. In full screen, use **Picture-in-picture** or **Close**.
- Closing the window (or dragging it away) turns the eye off.

**Touch control (full screen).** Expand the window to full screen and use the app with your fingers:
taps, swipes, scrolling, pinch. A picture-in-picture window can't receive touches (Android keeps
them for its own window controls), so the small window stays view only.

- The **Back** button in the viewer's controls sends Back to the app on the virtual screen.
- Your tablet's own **Back** (gesture or navigation bar) and **Home** stay yours: Back shrinks the
  viewer to picture-in-picture, Home leaves it. After each touch, MoreDisplay gives Android's input
  focus back to your screen so that this always works.
- Touches only ever go to MoreDisplay's virtual screens, never to your own screen.
- **Settings → Eye controls**: *Touch control in the full-screen eye* (on) and *Show touches* (off).

There are four ways to toggle it, each with its own switch in **Settings**:

| Control | How to use it |
| --- | --- |
| **Eye button on display cards** | The eye icon on each virtual screen in the Displays tab |
| **Quick Settings tile** | Edit your Quick Settings and add the **Eye** tile. It toggles the last watched screen |
| **Home-screen widget** | Add the **MoreDisplay eye** widget. It toggles the last watched screen |
| **adb command** | `--es action eye`, see *Advanced* below |

The tile and the widget need a "last watched" screen, so use the card button (or adb) once first.

Limitations: one viewer at a time (showing another screen switches the viewer to it); apps that
protect their content (DRM video, secure screens) show black; hiding the eye's tiny "display
changed" notice from the watched apps works on the Vector channel only.

### Cast a virtual screen to a Chromecast

Run an app on a virtual screen at the resolution you want (for example 1280×720) and show that screen
on your TV **1:1**, with the app's sound, while your own screen stays free. Your screen's resolution
plays no part.

1. Create a virtual screen at the size you want (Displays tab), and give the app a policy with
   **Open on** → that screen.
2. **Settings → Cast**: pick that screen, and optionally the quality, playback, frame rate and sound.
3. **Displays tab → Cast display …**, pick your Chromecast. The card shows *Casting to …* and a
   notification lets you stop it from anywhere.

How it works: the screen's picture goes to the tablet's hardware video encoder (no scaling), and the
Chromecast plays it as a live stream from a small server in MoreDisplay, using Google's built-in
player (no account or registration needed). The eye keeps working while casting.

- **Delay**: about 4–7 seconds with **Playback: Smooth** (the default, which keeps more video ready on the
  TV so Wi-Fi hiccups don't cause pauses), about 2–5 seconds with **Low delay**. Picture and sound are
  delayed together, so they stay in sync.
- **Sound** (Vector only): the sound of the apps set to *Open on* the cast screen goes to the TV
  instead of the tablet speaker, until the cast stops. Apps that forbid sound capture stay silent.
  With Shizuku, casting is picture-only.
- **Copy-protected video stays black** (Netflix, Disney+, Prime Video and similar): Android won't put
  it on a virtual screen, by design. Use those apps' own Cast button.
- Needs Google Play services, and the tablet and the Chromecast on the same Wi-Fi.
- If the app pauses when the tablet screen turns off, turn on **Keep the tablet awake while casting**.

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

# Always open an app on screen 2 (and let it see only screen 2)
adb shell am start -n io.github.ynkcc.moredisplay/.CommandActivity \
  --es action policy-set --es target_package com.example.app --es mode only --eia display_ids 2 --ei launch_display_id 2

# Make an app's screen recordings capture screen 2
adb shell am start -n io.github.ynkcc.moredisplay/.CommandActivity \
  --es action policy-set --es target_package com.example.app --es mode all --ei record_display_id 2

# The eye: show / hide / toggle screen 2 in the picture-in-picture viewer
adb shell am start -n io.github.ynkcc.moredisplay/.CommandActivity --es action eye --ei display_id 2 --es state on
adb shell am start -n io.github.ynkcc.moredisplay/.CommandActivity --es action eye --es state off

# Cast: start (display from Settings, last Chromecast used) / stop
adb shell am start -n io.github.ynkcc.moredisplay/.CommandActivity --es action cast --es state on
adb shell am start -n io.github.ynkcc.moredisplay/.CommandActivity --es action cast --es state off

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
| `policy-set` | Add or replace a rule | `target_package` or `uid`, `mode` (`all` / `only` / `hide`), `display_ids`, `launch_display_id`, `orientation` (`app` / `landscape`), `record_display_id`, `operated_display_id` |
| `policy-list` | Show all rules | |
| `policy-remove` | Delete a rule | `target_package` or `uid` |
| `displays-for-uid` | Which screens an app can see | `target_package` or `uid` |
| `shizuku-permit` | Ask for the Shizuku permission | |
| `cast` | Cast a virtual screen to the last Chromecast used | `state` (`on` / `off`), `display_id` (default: Settings → Cast) |
| `eye` | Show or hide a virtual screen in the PiP viewer | `display_id` (default: last watched), `state` (`on` / `off` / `toggle`, default `toggle`) |

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
| My first virtual screen is `#2`, not `#1` | Android picks the number, not MoreDisplay: each new display gets the next free number and numbers aren't reused until a restart. Something else used `#1` (see **Other displays** at the bottom of the Displays tab), or a screen was removed earlier. Always use the number shown in the app. |
| Cast: *Connect the tablet to the same Wi-Fi* | The tablet and the Chromecast must be on the same Wi-Fi network. |
| Cast: the TV shows an error or stays loading | Check that the tablet and the Chromecast are on the same network and that no VPN or "client isolation" (common on guest Wi-Fi) blocks them from talking to each other. |
| Cast: no sound on the TV | Sound needs the Vector channel and an app set to *Open on* the cast screen. Some apps forbid sound capture. After updating MoreDisplay, reboot once (the sound needs the updated module in Android's system process). |
| Cast: the picture stutters or pauses | Use **Playback: Smooth** in Settings → Cast, try a lower quality, or bring the Chromecast closer to the router (5 GHz Wi-Fi helps). |
| Cast error with a `CAST-xx` code | See *Cast error codes* below. |
| The eye tile/widget says *No display* | Tap the eye on a display card once, so it knows which screen to show. |
| The eye window is black | The app on that screen protects its content (DRM/secure), or nothing is drawn there yet. |
| Setup not back after a reboot | Allow Autostart for MoreDisplay, or open the app and tap Connect. Displays made through Shizuku come back once Shizuku is running. |

---

### Cast error codes

When a cast fails, the Displays tab shows a code and what was found (you can select and copy it). The
same line is in the log under the tag `MoreDisplay_Cast`, which also prints the cast's counters every
10 seconds while casting.

| Code | Meaning | What to check |
| --- | --- | --- |
| `CAST-01` | No Wi-Fi address | Connect the tablet to Wi-Fi (the same network as the Chromecast). |
| `CAST-02` | The video encoder couldn't start | Try a lower quality or 30 fps in Settings → Cast, or a smaller display (e.g. 1280×720). |
| `CAST-03` | The picture copier (OpenGL) failed | Report it with the details. |
| `CAST-04` | The display's picture couldn't be taken | Make sure the display still exists and the channel (Vector/Shizuku) is connected. |
| `CAST-05` | No video was produced | The details give the counters: `display frames 0` = the display sent nothing; `encoded 0` = the encoder produced nothing; few `key frames` = the encoder ignores key-frame requests. Report it with the details. |
| `CAST-06` | Couldn't connect to the Cast device | Is the Chromecast on and on the same network? Try again; the details include Google's Cast status number. |
| `CAST-07` | The Chromecast couldn't play the stream | Report it with the Cast status number in the details. |
| `CAST-08` | The Chromecast never reached the tablet | The two can't talk to each other: same network? No VPN on the tablet? Guest Wi-Fi often isolates devices. |
| `CAST-09` | The display to cast doesn't exist | Pick an existing display in Settings → Cast. |
| `CAST-10` | Cast isn't available | Google Play services are needed on the tablet. |

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
