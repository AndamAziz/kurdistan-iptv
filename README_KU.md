# KURDISTAN IPTV — ڕێنمایی دروستکردنی APK

## چی چاککرا لە پڕۆژەکەدا

| # | کێشە | چارەسەر |
|---|------|---------|
| 1 | `styles.xml` و `themes.xml` هەردووکیان `Theme.KurdistanIPTV` ـیان پێناسە کردبوو → **build سەرکەوتوو نەدەبوو** (Duplicate resources) | `styles.xml` سڕدرایەوە |
| 2 | لە `AndroidManifest.xml` دا هیچ `android:icon` نەبوو، هەروەها بۆ Android 7–7.1 هیچ ئایکۆنێکی PNG نەبوو | `android:icon` و `android:roundIcon` زیادکران + ئایکۆنی PNG بۆ هەر ٥ دانسیتی دروستکرا |
| 3 | `nativeBar` بە `FrameLayout` بوو بەڵام `layout_weight` ـی بەکاردەهێنا → ناونیشان و دوگمەی Close لەسەر یەک دەکەوتن | گۆڕدرا بۆ `LinearLayout` (horizontal) + `ellipsize` بۆ ناونیشانە درێژەکان |
| 4 | هیچ ڕێگایەکی خۆکاری build نەبوو | `.github/workflows/build-apk.yml` زیادکرا |

---

## ڕێگا ١ — دروستکردنی APK لە GitHub (پێشنیارکراو، هیچ شتێک دانامەزرێنیت)

١. بڕۆ بۆ **https://github.com/new** → ناوێک بنووسە (بۆ نموونە `kurdistan-iptv`) → **Private** هەڵبژێرە → `Create repository`

٢. لە پەڕەی ڕیپۆکە کلیک لە **uploading an existing file** بکە

٣. تەواوی ناوەڕۆکی فۆڵدەری `KURDISTAN_IPTV_Android` ڕابکێشە بۆ ناو پەڕەکە (ئاگاداربە: ناوەڕۆکەکەی، نەک خودی فۆڵدەرەکە)

٤. کلیک لە **Commit changes**

٥. بڕۆ بۆ تابی **Actions** → بینەریی `Build APK` → چاوەڕێی ٣–٥ خولەک بکە تا ✅ سەوز دەبێت

٦. کلیک لەسەر run ـەکە → لە خوارەوە لە بەشی **Artifacts** دا `KURDISTAN-IPTV-debug-apk` دابەزێنە

APK ـەکە لە ناو ئەو ZIP ـەدایە. بینێرە بۆ مۆبایلەکەت و دایبمەزرێنە (پێویستە *Install from unknown sources* چالاک بکەیت).

---

## ڕێگا ٢ — لەسەر کۆمپیوتەری خۆت (پێویستی بە دابەزاندنی گەورەیە)

١. Android Studio دابەزێنە لە https://developer.android.com/studio (≈ ١ گیگابایت)

٢. کاتی یەکەم جار کردنەوەی، ڕێگە بدە SDK دابەزێنێت (≈ ٥–٧ گیگابایت)

٣. `File > Open` → فۆڵدەری `KURDISTAN_IPTV_Android` هەڵبژێرە

٤. چاوەڕێی Gradle sync بکە

٥. `Build > Build Bundle(s) / APK(s) > Build APK(s)`

٦. APK لێرە دەبێت: `app\build\outputs\apk\debug\app-debug.apk`

---

## زانیاری ئەپ

- ناو: **KURDISTAN IPTV**
- Package: `com.kurdistan.iptv`
- کەمترین وەشان: Android 7.0 (API 24)
- کۆدی چالاککردن: `1015`
- پلەیەر: Media3 ExoPlayer (HLS + TS) لەگەڵ یەدەگی WebView

> **ئاگاداری:** لینکەکانی پەخش وەک خۆیان ماونەتەوە. مافی بڵاوکردنەوەیان پشکنین نەکراوە — تەنها ئەو کەناڵانە بەکاربهێنە کە مۆڵەتت هەیە.
