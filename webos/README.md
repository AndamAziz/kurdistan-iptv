# KURDISTAN IPTV for LG Smart TV (webOS)

The TV app is the same page as the Android app (`app/src/main/assets/index.html`),
plus `webos-player.js`, which gives the page the `AndroidPlayer` it expects and
plays through the TV's own `<video>` (LG's hardware media pipeline: HLS, MPEG-TS,
MP4, MKV). Nothing in the Android, Windows or Samsung apps changes.

| File | What it is |
|---|---|
| `appinfo.json` | The webOS app description (id `uk.andam.kurdistaniptv`) |
| `webos-player.js` | `AndroidPlayer` for the TV: the player, its on-screen bar and the remote keys |
| `build.js` | Copies the page in, applies the three TV changes, writes `webos/build/` |
| `icon.png`, `largeIcon.png`, `splash.png` | 80×80 and 130×130 icons, 1920×1080 splash |

## The remote

| Where | Key | Does |
|---|---|---|
| App | Arrows / OK, Magic Remote pointer | Move / choose (the app's own TV layout) |
| App | Back | Closes what is open; on the home screen, press twice to go back to the TV |
| Player | Back, Stop | Close the player |
| Player | Up / Down, CH+ / CH− | Channel or episode before / after |
| Player | Left / Right, ⏪ / ⏩ | Films: 10 s back / 30 s forward. Live: show the bar |
| Player | OK, Play / Pause, pointer click | Films: pause / resume. Live: show the bar |
| Player | Info | Show / hide the bar |

A live channel that drops is retried 3 times on its own; after that the
screen says so and OK tries again. A film opens where it was left.

**Not on the TV yet:** subtitles (their buttons are hidden), choosing the audio
track, and sending our own User-Agent (a `<video>` sends the TV's). Links
(Telegram, WhatsApp, e-mail) are shown as text instead of opening another app.

---

## 1. Build the app folder

Needs [Node.js](https://nodejs.org).

```bash
node webos/build.js 1.0.261
```

The version must be `x.y.z` and higher than the one uploaded before. The result
is in `webos/build/`.

## 2. Make the .ipk

```bash
npm install -g @webos-tools/cli
ares-package webos/build -o .
```

This writes `uk.andam.kurdistaniptv_1.0.261_all.ipk`. `ares-package --check webos/build`
checks the folder without packaging it.

## 3. Try it

- **webOS TV Simulator** (free from LG's webOS TV developer site, for Windows, macOS
  and Linux): `ares-launch -s 24 webos/build` opens the app in the webOS 24 simulator.
- **A real LG TV in Developer Mode:** install the *Developer Mode* app from the LG
  Content Store, sign in with the LG developer account, turn on Dev Mode, then
  `ares-setup-device` (the TV's IP and passphrase) and
  `ares-install -d <tv> uk.andam.kurdistaniptv_1.0.261_all.ipk`.

## 4. Upload

LG Seller Lounge (seller.lgappstv.com) → Apps → new app → upload the `.ipk`,
then the store texts, images and the test account for LG's QA.

What was tested here: the TV build in Chromium at 1920×1080 with LG's key codes
(activation, playlist loading straight from the provider, the TV layout, opening a
channel, next / previous channel, retries and the error message, film seek / pause /
resume / position kept, leaving and coming back, Back and exit to the launcher, the
hidden updater and subtitle buttons). It has **not** been run on a real LG TV or
in LG's simulator yet; in particular, whether every LG model plays a bare MPEG-TS
live stream through `<video>`, and whether IPTV servers without CORS headers answer
the TV app, still has to be checked there.
