# KURDISTAN IPTV for Samsung Smart TV (Tizen)

The TV app is the same page as the Android app (`app/src/main/assets/index.html`),
plus `tizen-player.js`, which gives the page the `AndroidPlayer` it expects and
plays through Samsung's own player (`webapis.avplay`: HLS, MPEG-TS, MP4, MKV).
Nothing in the Android or Windows apps changes.

| File | What it is |
|---|---|
| `config.xml` | The Tizen app description (package `KurdIPTV01`, app id `KurdIPTV01.KurdistanIPTV`, Tizen 6.0+) |
| `tizen-player.js` | `AndroidPlayer` for the TV: the AVPlay player, its on-screen bar and the remote keys |
| `build.js` | Copies the page in, applies the three Tizen changes, writes `tizen/build/` |
| `icon.png` | 512×423 app icon |

## The remote

| Where | Key | Does |
|---|---|---|
| App | Arrows / OK | Move / choose (the app's own TV layout) |
| App | Back | Closes what is open; on the home screen, press twice to exit |
| Player | Back, Stop | Close the player |
| Player | Up / Down, CH+ / CH− | Channel or episode before / after |
| Player | Left / Right, ⏪ / ⏩ | Films: 10 s back / 30 s forward. Live: show the bar |
| Player | OK, Play/Pause | Films: pause / resume. Live: show the bar |
| Player | Info | Show / hide the bar |

A live channel that drops is retried 3 times on its own; after that the
screen says so and OK tries again. A film opens where it was left.

**Not on the TV yet:** subtitles (their buttons are hidden on the TV) and
choosing the audio track.

---

## 1. Build the app folder

Needs [Node.js](https://nodejs.org) (any recent version).

```bash
node tizen/build.js 1.0.260
```

The version must be `x.y.z` and **higher than the one uploaded before** every
time you upload to the Seller Office. Without a number it uses `1.0.<commits>`.
The result is in `tizen/build/` (index.html, channels.js, welcome.mp3,
tizen-player.js, config.xml, icon.png).

## 2. Install Tizen Studio (once)

1. Download **Tizen Studio** (with IDE) from
   <https://developer.tizen.org/development/tizen-studio/download> and install it,
   for example in `C:\tizen-studio`.
2. Open **Package Manager** (it opens after installing, or `C:\tizen-studio\package-manager\package-manager.exe`).
3. **Main SDK** tab: install **6.0 → TV** (or "Tizen SDK tools").
4. **Extension SDK** tab: install **TV Extensions-6.0** and **Samsung Certificate Extension**.

## 3. Make the Samsung certificate (once)

The `.wgt` must be signed with a **Samsung** certificate, tied to your Samsung account.

1. In Tizen Studio: **Tools → Certificate Manager → +**.
2. Choose **Samsung**, then **TV**.
3. Profile name: `KurdIPTV`.
4. **Author certificate:** "Create a new author certificate". Name `Andam Aziz`, a password you will remember.
   Keep the backup of `author.p12` and the password somewhere safe: every future
   update must be signed with the **same** author certificate.
5. Sign in with your **Samsung account** when it asks.
6. **Distributor certificate:** privilege level **Public**. It asks for device IDs
   (DUID) for testing; without a TV, leave the list as it is and continue.
7. Finish. The profile `KurdIPTV` now appears in Certificate Manager. Make it **active**.

## 4. Make the .wgt

### Windows (PowerShell)
```powershell
cd C:\path\to\kurdistan-iptv
node tizen\build.js 1.0.260
C:\tizen-studio\tools\ide\bin\tizen.bat package -t wgt -s KurdIPTV -- tizen\build
```

### macOS / Linux
```bash
node tizen/build.js 1.0.260
~/tizen-studio/tools/ide/bin/tizen package -t wgt -s KurdIPTV -- tizen/build
```

The signed package appears in `tizen/build/` as `KURDISTAN IPTV.wgt`.
**Rename it to lowercase without spaces**, e.g. `kurdistan-iptv.wgt`
(the Seller Office only takes a lowercase `.wgt`).

Instead of the command line you can also: **File → Import → Tizen → Tizen Project**,
choose `tizen/build`, then right-click the project → **Build Signed Package**.

## 5. Upload

Samsung Apps TV Seller Office → your app → **App Package → Upload Package → Choose a file**
→ `kurdistan-iptv.wgt` → **Done**. Then fill in App Images, Service Info, Country/Region
and send it for verification.

## Testing without a Samsung TV

- **Tizen Studio TV emulator** (Emulator Manager, if a TV image is offered for your
  TV Extensions version): the app, the layout and the remote keys can be tried; an
  emulator does not play every stream the way a real TV does.
- **Samsung Remote Test Lab** (Samsung Developers site), if it offers TVs for your
  account: a real Samsung TV in Samsung's lab, controlled from your browser.
- Samsung's verification team tests on real TVs and reports what fails.

What was tested here: the TV build in Chromium at 1920×1080 with a stand-in for
`webapis.avplay` and `tizen` that records every call (activation, playlist loading,
the TV layout, opening a channel, next / previous channel, retries and the error
message, film seek / pause / resume / position kept, suspend and restore, Back and
exit, the hidden updater and subtitle buttons). It has **not** been run on a real
Samsung TV yet.
