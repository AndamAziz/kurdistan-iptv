# Releasing KURDISTAN IPTV

A checklist built from what went wrong before. Follow it for every new version.

## 1. Before merging a PR

- [ ] CI is green on the PR, and the unit tests pass for all three flavours
      (`direct`, `play`, `amazon`, `huawei`).
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

## Which file goes where (never mix them)

| Store / users | File | Where to get it |
|---|---|---|
| GitHub, Downloader, APKPure (phones, Android TV) | `KURDISTAN-IPTV.apk` (universal), `-arm64-v8a.apk`, `-armeabi-v7a.apk` | `https://github.com/AndamAziz/kurdistan-iptv-releases/releases/latest/download/<file>`: always the newest; Downloader codes 4669811, 5228492, 8865694 point here |
| Windows | `KURDISTAN-IPTV-Setup.exe` | same address as above |
| Amazon Appstore (Fire TV) | `KURDISTAN-IPTV-Amazon.apk` (no channels, no starting playlist) | `https://github.com/AndamAziz/kurdistan-iptv-releases/releases/download/amazon/KURDISTAN-IPTV-Amazon.apk`: always the newest Amazon build; never put it on Downloader |
| Huawei AppGallery | `KURDISTAN-IPTV-Huawei.apk` (the Play build's playlist and channels, nothing from Google, no Movies Online) | `https://github.com/AndamAziz/kurdistan-iptv-releases/releases/download/huawei/KURDISTAN-IPTV-Huawei.apk`: always the newest Huawei build; never put it on Downloader |
| Google Play | the bundle (`.aab`) | sent by CI only when asked (Actions → Build APK → Run workflow → send_to_play) |
| Samsung / LG | `.wgt` / `.ipk` | built by hand (table below) |
| VIDAA (Hisense) | `KURDISTAN-IPTV-VIDAA-<v>.zip` (player only, like Amazon) | `node vidaa/build.js <v>`; see `vidaa/README.md` |

The version number of every build is the CI run number (`1.0 (<run>)`, version code `<run>`).

## 3. Test on real devices before sending to the stores

- [ ] Phone: live channel, film, series episode, subtitles.
- [ ] TV (Fire TV / Android TV): the remote keys CH+/CH-, ▲▼, 0-9, OK and Back.

Samsung and LG test **one version at a time**, often for weeks. A version
sent with a bug blocks the next one until its test ends.

## 4. Store packages

| Store | File | Notes |
|---|---|---|
| Amazon | `KURDISTAN-IPTV-Amazon.apk` from the fixed Amazon address above (or the run's `KURDISTAN-IPTV-Amazon-1.0-<run>` artifact) | Check: versionCode = run number, cert SHA-256 `a1ae61bf…`, no `com/google/android/gms`, no install permission. Upload under **Upcoming Version → Replace** APK1. Category stays **Photo & Video → Video Player**. It must carry **no channels and no starting playlist** (its own `app/src/amazon/assets/channels.js`): Amazon refuses an app that streams from third-party sources out of the box. |
| Huawei | `KURDISTAN-IPTV-Huawei.apk` from the fixed Huawei address above (or the run's `KURDISTAN-IPTV-Huawei-1.0-<run>` artifact) | Check: versionCode = run number, no `com/google/android/gms`, no `com/google/android/play/core`, no install permission. Upload in **AppGallery Connect → My apps → KURDISTAN IPTV → Version information → Software packages**. AppGallery updates it; the app never installs an APK itself. |
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
