# KURDISTAN IPTV - R8 rules for the release build
#
# The libraries (Media3, OkHttp, Play services, the FFmpeg decoders) bring their
# own rules. These are only what this app itself needs.

# ---- the page's link to the app --------------------------------------------
# index.html calls AndroidPlayer.playList(...), .probeStream(...), .http(...),
# .subSave(...), .isTv() ... by name. The web view finds a method by looking
# for that exact name on the object, so none of them may be renamed or removed.
-keepattributes *Annotation*
-keepclassmembers class com.kurdistan.iptv.MainActivity$AndroidBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.kurdistan.iptv.MainActivity$AndroidBridge { <init>(...); }
# the same for any other object handed to a web view
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---- readable crash reports -------------------------------------------------
# The mapping file (app/build/outputs/mapping/<variant>/mapping.txt) turns a
# release stack trace back into real names; keep it for every release you ship.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- native code -------------------------------------------------------------
# The FFmpeg decoders (nextlib-media3ext) ship their own keep rules for their
# native methods and for androidx.media3.decoder.VideoDecoderOutputBuffer, which
# their native code reaches by name. The app has no native code of its own.
