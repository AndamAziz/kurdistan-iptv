# KURDISTAN IPTV for VIDAA / HomeOS TVs (Hisense)

**Status:** ready, waiting for VIDAA's partner answer (contact sent 11 Nov 2026
from info@andam.uk). Their technical rules come after an NDA; when they arrive,
check them against "What may still need changing" below.

The TV app is the same page as the Android app (`app/src/main/assets/index.html`)
plus `vidaa-player.js` (built from `webos/webos-player.js`). Like the **Amazon**
build it is a **player only**: no channels and no starting playlist (it takes
`app/src/amazon/assets/channels.js`). The user adds their own Xtream, M3U or
MAC Portal. Nothing in the Android, Windows, Samsung or LG apps changes.

| File | What it is |
|---|---|
| `vidaa-player.js` | `AndroidPlayer` for the TV: player, on-screen bar, remote keys |
| `vidaa-extras.js` | VIDAA-only fixes, loaded last: remote focus (nearest row first, the highlight kept when a page is drawn again), M3U logos and groups in every spelling (`TVG-LOGO`, single quotes, `#EXTIMG`, `#EXTGRP` …), the guide (EPG) for M3U from its `url-tvg` XMLTV file, clearer speed-test names |
| `lib/hls.min.js` | hls.js 1.5.20 (Apache-2.0): HLS on TVs that cannot play it themselves |
| `lib/mpegts.min.js` | mpegts.js 1.8.0 (Apache-2.0): live MPEG-TS (Xtream) over Media Source |
| `build.js` | Copies the page in, applies the TV changes, writes `build/` and `dist/*.zip` |
| `server/kurdistan-vidaa.nginx.conf` | Only if VIDAA asks for a hosted URL: an isolated static site |
| `icon.png`, `largeIcon.png`, `splash.png` | Icons and splash (same as LG) |

Everything is inside `vidaa/`; the build writes nothing outside it.

## Build

Needs Node.js only (no packages to install).

```bash
node vidaa/build.js 1.0.320
```

Writes `vidaa/build/` (the app's files) and
`vidaa/dist/KURDISTAN-IPTV-VIDAA-1.0.320.zip` (the same files zipped, for an upload).
The build stops if the empty Amazon channel list is not empty.

For our own address (`tv.andam.uk`, used through red.app), build with the
starting playlist **ANDAM IPTV**, as in the GitHub app:

```bash
node vidaa/build.js 1.0.338 --channels
```

This takes the full `app/src/main/assets/channels.js` and writes
`KURDISTAN-IPTV-VIDAA-<v>-channels.zip`. The store upload is built without
`--channels` and stays a player only. Movies Online is in no VIDAA build: the
TV player has no `embedOk()`.

## How each stream plays

| Stream | Played by |
|---|---|
| `.m3u8` (HLS) | The TV's own player if it has HLS, otherwise hls.js |
| `.ts`, a live Xtream address | mpegts.js over Media Source, otherwise the TV |
| a live `.ts` that the panel redirects to `.m3u8` (HLS panels) | mpegts.js fails, so the same channel is played again as `.m3u8` in hls.js; that server then starts in HLS for the rest of the session |
| `.mp4`, `.mkv` … (films) | The TV's own `<video>` |
| MAC Portal | The page asks the portal for the address first (as on LG) |

## The remote

As on LG (see `webos/README.md`). Back is any of the codes VIDAA models send
(8, 461, 10009, Escape); CH+ / CH− are 33/427 and 34/428; Play/Pause 463 or 179.
In a text box, Backspace deletes a letter instead of going back.

## Giving it to VIDAA

- **If they want a package:** upload `vidaa/dist/KURDISTAN-IPTV-VIDAA-<version>.zip`.
- **If they want a URL:** follow the steps at the top of
  `server/kurdistan-vidaa.nginx.conf` (own folder `/var/www/kurdistan-vidaa`, own
  sub-domain `tv.andam.uk`, static files only), then give them `https://tv.andam.uk/`.

Reviewer notes, as for Amazon: the app has no content; to try playback use
"+ Add playlist" → "M3U by URL" with
`https://raw.githubusercontent.com/AndamAziz/kurdistan-iptv/main/docs/amazon-review/test-playlist.m3u`.

## What may still need changing after VIDAA's rules

- **Cross-site requests (CORS):** as a package the TV usually lets the app ask
  any server (as LG and Samsung do). As a hosted URL, the TV's browser may block
  M3U/Xtream servers that do not allow it; then a small proxy on the VPS would
  be needed - not added until we know it is.
- **Key codes:** the common ones are handled; VIDAA's list may add a key.
- **Exit:** the app closes with `window.close()`; VIDAA may name another call.
- **Manifest / icon sizes:** VIDAA's packaging rules may ask for a file or size
  not here yet.

## Tested (Chromium, 1920×1080)

Opens empty with the add-playlist button; an M3U added through the form loads;
HLS played through hls.js, MPEG-TS through mpegts.js, MP4 natively; Up / CH+
(427) / the number keys change channel; Back (8) closes the player, and on the
home screen asks to press again to exit. Not yet on a real VIDAA TV.
