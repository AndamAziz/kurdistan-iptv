# Releasing KURDISTAN IPTV

A checklist built from what went wrong before. Follow it for every new version.

## 1. Before merging a PR

- [ ] CI is green on the PR, and the unit tests pass for all three flavours
      (`direct`, `play`, `amazon`).
- [ ] `WHATSNEW.md` has a new first paragraph under `[en]`, `[ku]` and `[ar]`.
      The first English paragraphs (up to 500 characters) become the Google Play
      release notes, and the whole file becomes the GitHub release text.
- [ ] Nothing was added that Amazon calls an "ad network": no ads, analytics,
      tracking or Google Play Services in the `amazon` flavour.

## 2. Right after merging (CI on `main`)

- [ ] The run on `main` is green: build, windows, play.
- [ ] The fixed links point at the new tag (`v1.0.<run>`), and their sizes are right:
      - `KURDISTAN-IPTV.apk` (universal) is about 27 MB. A file of about 8 MB
        under this name means the arm64 one was published by mistake (v1.0.271).
      - `KURDISTAN-IPTV-arm64-v8a.apk` is about 8.7 MB.
      - `KURDISTAN-IPTV-armeabi-v7a.apk` is about 8.2 MB.
      - `KURDISTAN-IPTV-Setup.exe` is about 119 MB.
- [ ] Google Play is **not** sent anything by a merge: each upload cancels the
      review already under way and starts it again. When a review is not
      running, send a build by hand: Actions → Build APK → Run workflow on
      `main`, tick **send_to_play**. It lands in **Alpha**; promote it to
      Production from the Play Console when ready, then wait for the review
      before merging anything that should go to Play next.

## 3. Test on real devices before sending to the stores

- [ ] Phone: live channel, film, series episode, subtitles.
- [ ] TV (Fire TV / Android TV): the remote keys CH+/CH-, ▲▼, 0-9, OK and Back.

Samsung and LG test **one version at a time**, often for weeks. A version
sent with a bug blocks the next one until its test ends.

## 4. Store packages

| Store | File | Notes |
|---|---|---|
| Amazon | `amazon/release/app-amazon-release.apk` from the run's artifact | Check: versionCode = run number, cert SHA-256 `a1ae61bf…`, no `com/google/android/gms`, no install permission. Upload under **Upcoming Version → Replace** APK1. Category stays **Photo & Video → Video Player**. |
| Samsung | `node tizen/build.js 1.0.<run>`, then sign with `tizen.bat package -t wgt -s KurdIPTV -- .` | Only when no model group is still in Testing. Choose **Optional** update and **Yes** to minor defects. Never choose Mandatory. |
| LG | `node webos/build.js 1.0.<run>` and `RES=1280x720 node webos/build.js 1.0.<run>`, then `ares-package` | Upload both `.ipk` files (1920x1080 and 1280x720). This is not possible while QA is running. |

## 5. Settings that must not change without a check

- The repository can be public or private. While private, GitHub keeps only
  500 MB of build files. The first CI step deletes old ones, but check the
  next run after any change.
- Store categories, content rating and the privacy policy link.
- Secrets live only in GitHub Secrets, Supabase Secrets and your own backup,
  never in the code.

## 6. Keep a backup of these (losing them means no more updates)

- The Android keystore (`.jks`) and its passwords. GitHub Secrets cannot be
  read back.
- Samsung `author.p12` and its password (Tizen Studio certificate profile
  `KurdIPTV`).
