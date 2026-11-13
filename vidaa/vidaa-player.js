/*
 * KURDISTAN IPTV on a VIDAA / HomeOS TV (Hisense, Toshiba and others).
 *
 * Built from webos/webos-player.js: the page was written for Android and asks
 * for `AndroidPlayer`, so on the TV it is given exactly that, with the same
 * methods and arguments, and the page itself does not change.
 *
 * VIDAA's browser plays through an HTML5 <video>. Not every model plays HLS or
 * MPEG-TS there by itself, so this player picks, for each stream:
 *   .m3u8                 the TV's own HLS when it has it, else hls.js (lib/)
 *   .ts / a live Xtream   mpegts.js over Media Source when it can, else the TV
 *   .mp4 .mkv ...         the TV's own <video>
 * Both libraries are in this folder: nothing is loaded from the internet.
 *
 * The remote: the same as on LG (see webos/README.md). Back is any of the
 * codes VIDAA models send for it (8, 461, 10009, Escape).
 *
 * Not yet on the TV: subtitles (their buttons are hidden), audio-track choice,
 * and a User-Agent of our own (a <video> sends the TV's).
 */
(function () {
  "use strict";
  var W = window, D = document;

  /* what build.js tells the page about this build */
  W.KIPTV_DIRECT = true;            /* no app in between: ask servers directly */
  W.KIPTV_NOUPD = true;             /* updates come from the VIDAA app store */
  var BUILD = String(W.KIPTV_VIDAA_BUILD || "0");

  var K = {
    BACK: 461, ENTER: 13, UP: 38, DOWN: 40, LEFT: 37, RIGHT: 39,
    PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, RW: 412,
    CHUP: 33, CHDOWN: 34, INFO: 457, ESC: 27
  };
  /* VIDAA remotes are not all alike: each key is any of the codes seen for it */
  function isBack(k) { return k === 461 || k === 8 || k === 10009 || k === 27 }
  function isChUp(k) { return k === 33 || k === 427 }
  function isChDown(k) { return k === 34 || k === 428 }
  function isPlayPause(k) { return k === 463 || k === 179 }

  function lang() { return (W.lang === "ku" || W.lang === "ar") ? W.lang : "en" }
  var WORDS = {
    en: { loading: "Loading…", buffering: "Buffering…", fail: "This stream cannot be played right now", codec: "This TV cannot play the video format of this channel",
          again: "OK: try again · Back: close", live: "LIVE", exit: "Press Back again to exit", paused: "Paused",
          all: "All channels", phint: "◀ ▶ category · OK watch · Back close" },
    ku: { loading: "بارکردن…", buffering: "چاوەڕێ بکە…", fail: "ئەم پەخشە ئێستا لێنادرێت", codec: "ئەم تیڤییە ناتوانێت جۆری ڤیدیۆی ئەم کەناڵە لێبدات",
          again: "OK: دووبارە · Back: داخستن", live: "ڕاستەوخۆ", exit: "بۆ دەرچوون جارێکی تر Back دابگرە", paused: "وەستێنراو",
          all: "هەموو کەناڵەکان", phint: "◀ ▶ پۆل · OK سەیرکردن · Back داخستن" },
    ar: { loading: "جارٍ التحميل…", buffering: "جارٍ التخزين…", fail: "لا يمكن تشغيل هذا البث الآن", codec: "هذا التلفاز لا يدعم صيغة الفيديو لهذه القناة",
          again: "OK: إعادة · Back: إغلاق", live: "مباشر", exit: "اضغط رجوع مرة أخرى للخروج", paused: "متوقف",
          all: "كل القنوات", phint: "◀ ▶ الفئة · OK مشاهدة · Back إغلاق" }
  };
  function w(k) { return (WORDS[lang()] || WORDS.en)[k] }

  /* Subtitles are not shown by this player yet (Android's player draws them
     natively), so their buttons stay out of sight instead of doing nothing. */
  (function () {
    var st = D.createElement("style");
    st.textContent = "#dSubs,#setBody [onclick*=\"openSheet('subs')\"]{display:none!important}";
    (D.head || D.documentElement).appendChild(st);
  })();

  /* leaving the app: VIDAA closes an app's window */
  function exitApp() {
    try { W.close() } catch (e) {}
    try { if (W.history.length > 1) W.history.go(-(W.history.length - 1)) } catch (e) {}
  }

  /* ---------------- the player's screen ---------------- */
  var P = { on: false, list: [], i: 0, live: true, gen: 0, tries: 0, paused: false,
            dur: 0, pos: 0, osdT: 0, savedAt: 0, err: false, el: null, hidAt: 0 };

  var CSS =
    "html.wop-on body>*:not(#wop){visibility:hidden!important}" +
    "#wop,#wop *{box-sizing:border-box;margin:0;letter-spacing:normal;text-transform:none}" +
    "#wop{position:fixed;inset:0;z-index:2147483000;display:none;background:#000;color:#fff;" +
    "font-family:Arial,'Noto Sans Arabic',sans-serif}" +
    "#wop.on{display:block}" +
    "#wopVid{position:absolute;left:0;top:0;width:100%;height:100%;background:#000;object-fit:contain}" +
    "#wop .wop-bar{position:absolute;left:0;right:0;padding:36px 64px;transition:opacity .25s;opacity:0}" +
    "#wop.osd .wop-bar{opacity:1}" +
    "#wop .wop-top{top:0;background:linear-gradient(#000c,#0000)}" +
    "#wop .wop-bot{bottom:0;background:linear-gradient(#0000,#000c)}" +
    "#wop .wop-ttl{font-size:44px;font-weight:700;display:flex;align-items:center;gap:22px}" +
    "#wop .wop-live{font-size:22px;font-weight:800;letter-spacing:2px;padding:6px 14px;border-radius:8px;background:#ED2024}" +
    "#wop .wop-num{font-size:26px;color:#c9d1dc;margin-top:8px}" +
    "#wop .wop-prog{height:8px;border-radius:8px;background:#ffffff33;overflow:hidden}" +
    "#wop .wop-prog i{display:block;height:100%;width:0;background:#E8B33C}" +
    "#wop .wop-tm{display:flex;justify-content:space-between;font-size:26px;color:#dfe5ec;margin-top:14px}" +
    "#wop .wop-st{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);font-size:34px;font-weight:700;" +
    "padding:22px 40px;border-radius:18px;background:#0009;display:none;text-align:center;max-width:70%}" +
    "#wop .wop-st.on{display:block}#wop .wop-st small{display:block;font-size:24px;font-weight:400;color:#c9d1dc;margin-top:12px}" +
    /* the channel list over the picture */
    "#wop.pn .wop-bar{opacity:0!important}#wopPn{position:absolute;top:0;bottom:0;left:0;width:620px;background:linear-gradient(90deg,#05080ef2 80%,#05080e00);" +
    "padding:34px 40px 34px 34px;display:none}#wopPn.on{display:block}" +
    "html[dir=rtl] #wopPn{left:auto;right:0;padding:34px 34px 34px 40px;background:linear-gradient(270deg,#05080ef2 80%,#05080e00)}" +
    "#wopPn .vxg{display:flex;align-items:center;justify-content:space-between;font-size:30px;font-weight:800;color:#E8B33C;margin-bottom:6px}" +
    "#wopPn .vxg span{font-size:26px;color:#8a95a5}#wopPn .vxh{font-size:20px;color:#8a95a5;margin-bottom:18px}" +
    "#wopPn .vxr{display:flex;align-items:center;gap:16px;height:74px;padding:0 16px;border-radius:14px;margin-bottom:6px;color:#dfe5ec}" +
    "#wopPn .vxr b{width:58px;font-size:22px;color:#8a95a5;font-weight:600;text-align:center}" +
    "#wopPn .vxr img,#wopPn .vxr i{width:64px;height:48px;border-radius:8px;object-fit:contain;background:#ffffff14;flex:none}" +
    "#wopPn .vxr em{font-style:normal;font-size:26px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;flex:1}" +
    "#wopPn .vxr.now em{color:#E8B33C}#wopPn .vxr.on{background:#E8B33C;color:#111}#wopPn .vxr.on b,#wopPn .vxr.on em{color:#111}";

  function build() {
    if (P.el) return P.el;
    var s = D.createElement("style"); s.textContent = CSS; D.head.appendChild(s);
    var el = D.createElement("div"); el.id = "wop";
    el.innerHTML = '<video id="wopVid" autoplay></video>' +
      '<div class="wop-bar wop-top"><div class="wop-ttl"><span class="wop-live" id="wopLive"></span><span id="wopName"></span></div>' +
      '<div class="wop-num" id="wopNum"></div></div>' +
      '<div class="wop-bar wop-bot" id="wopBot"><div class="wop-prog"><i id="wopFill"></i></div>' +
      '<div class="wop-tm"><span id="wopPos"></span><span id="wopDur"></span></div></div>' +
      '<div class="wop-st" id="wopSt"></div><div id="wopPn"></div>';
    D.body.appendChild(el);
    /* a pointer remote or a mouse: a click on the picture is OK */
    el.addEventListener("click", function () { if (P.err) { P.tries = 0; playAt(P.i) } else togglePause() });
    P.el = el;
    return el;
  }
  function $(id) { return D.getElementById(id) }
  function fmt(ms) {
    var s = Math.max(0, Math.floor(ms / 1000)), h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60;
    s = s % 60;
    return (h ? h + ":" + (m < 10 ? "0" : "") : "") + m + ":" + (s < 10 ? "0" : "") + s;
  }
  function status(text, sub) {
    var st = $("wopSt"); if (!st) return;
    st.innerHTML = text ? text + (sub ? "<small>" + sub + "</small>" : "") : "";
    st.className = text ? "wop-st on" : "wop-st";
  }
  function osd(show) {
    if (!P.el) return;
    clearTimeout(P.osdT);
    if (show === false) { P.el.classList.remove("osd"); return }
    P.el.classList.add("osd");
    if (!P.paused && !P.err) P.osdT = setTimeout(function () { P.el.classList.remove("osd") }, 4500);
  }
  function drawInfo() {
    var it = P.list[P.i] || {};
    $("wopName").textContent = it.n || "";
    $("wopLive").textContent = P.live ? w("live") : "";
    $("wopLive").style.display = P.live ? "" : "none";
    $("wopNum").textContent = P.list.length > 1 ? (P.i + 1) + " / " + P.list.length : "";
    $("wopBot").style.visibility = P.live ? "hidden" : "visible";
  }
  function drawTime() {
    if (P.live || !P.el) return;
    $("wopPos").textContent = fmt(P.pos);
    $("wopDur").textContent = P.dur ? fmt(P.dur) : "";
    $("wopFill").style.width = P.dur ? Math.min(100, P.pos / P.dur * 100) + "%" : "0";
  }

  /* a film's place is kept, so it opens where it was left (the page's own store) */
  function savePos(force) {
    var it = P.list[P.i];
    if (!it || P.live || !P.dur || P.pos < 5000) return;
    if (!force && Date.now() - P.savedAt < 10000) return;
    P.savedAt = Date.now();
    try { if (W.savePos) W.savePos(it.u, P.pos, P.dur) } catch (e) {}
  }

  /* ---------------- playing ---------------- */
  /* the library playing the current stream, if any */
  var ENG = null;
  function engineStop() {
    var e = ENG; ENG = null;
    if (!e) return;
    try { if (e.destroy) e.destroy() } catch (x) {}
    try { if (e.unload) e.unload() } catch (x) {}
    try { if (e.detachMediaElement) e.detachMediaElement() } catch (x) {}
  }
  function stopAll() {
    P.gen++;
    engineStop();
    var v = $("wopVid");
    if (v) { try { v.pause(); v.removeAttribute("src"); while (v.firstChild) v.removeChild(v.firstChild); v.load() } catch (e) {} }
  }

  function playAt(i, startMs) {
    if (i < 0 || i >= P.list.length) return;
    savePos(true);
    stopAll();
    var gen = P.gen, it = P.list[i];
    P.i = i; P.live = !it.v; P.paused = false; P.err = false; P.pos = 0; P.dur = 0;
    drawInfo(); drawTime(); osd(true);
    status(w("loading"));
    portalLink(it, gen, function (one) {
      if (gen !== P.gen || !P.on) return;
      videoOpen(one, startMs || 0, gen);
    });
  }

  /* a MAC Portal item's address is a request for it: the page asks the
     portal (create_link) and the real address is played */
  function portalLink(it, gen, go) {
    if (!it.u || it.u.indexOf("kiptv_stk=1") < 0 || !W.kiptvResolve) { go(it); return }
    W.kiptvResolve(it.u).then(function (real) {
      var one = {}; for (var k in it) if (it.hasOwnProperty(k)) one[k] = it[k];
      one.u = real;
      go(one);
    }, function (e) { if (gen === P.gen && P.on) failed(e) });
  }

  /* a live .ts or a bare Xtream address is MPEG-TS, an .m3u8 is HLS, the rest
     the TV sniffs */
  function typeOf(it) {
    var u = String(it.u).split("?")[0].toLowerCase();
    if (/\.m3u8$/.test(u) || /\/hls\//.test(u)) return "application/x-mpegURL";
    if (/\.ts$/.test(u) || (!it.v && !/\.(mp4|mkv|m4v|mov|webm|avi)$/.test(u))) return "video/mp2t";
    return "";
  }
  /* Many Xtream panels send live channels as HLS: a live .ts address answers
     with a redirect to the same channel's .m3u8, which mpegts.js cannot read.
     When a live .ts fails in mpegts.js we try the .m3u8 address in hls.js, and
     remember that server so its next channel starts in HLS at once. If HLS
     fails there too, the server is forgotten and the next try is .ts again. */
  var HLS_HOSTS = {};
  function hostOf(u) { var m = /^(https?:\/\/[^\/?#]+)/i.exec(String(u)); return m ? m[1].toLowerCase() : "" }
  function isTsUrl(u) { return /\.ts(\?|#|$)/i.test(String(u)) }
  function asHls(u) { return String(u).replace(/\.ts(\?|#|$)/i, ".m3u8$1") }

  function nativeHls(v) {
    try { return !!(v.canPlayType("application/vnd.apple.mpegurl") || v.canPlayType("application/x-mpegURL")) } catch (e) { return false }
  }
  function mseTs() {
    try { return !!(W.mpegts && W.mpegts.isSupported() && W.mpegts.getFeatureList().mseLivePlayback) } catch (e) { return false }
  }

  /* hls.js tuned for TVs: a slow processor and Wi-Fi, little memory for media */
  var STALL_MS = 15000;
  var CODEC_ERR = { manifestIncompatibleCodecsError: 1, bufferIncompatibleCodecsError: 1, bufferAddCodecError: 1 };
  function hlsConfig(startMs) {
    return {
      startPosition: startMs > 0 && !P.live ? startMs / 1000 : -1,
      enableWorker: true, lowLatencyMode: false,
      /* start on a modest quality and let it climb: the first picture comes sooner */
      startLevel: -1, testBandwidth: true,
      capLevelToPlayerSize: true, capLevelOnFPSDrop: true,
      /* a little further from the live edge: more room before it has to wait */
      liveSyncDurationCount: 4, liveMaxLatencyDurationCount: 12, liveDurationInfinity: true,
      maxBufferLength: 20, maxMaxBufferLength: 40, maxBufferSize: 30 * 1000 * 1000,
      backBufferLength: 15, maxBufferHole: 1, nudgeMaxRetry: 6,
      fragLoadingMaxRetry: 6, manifestLoadingMaxRetry: 3, levelLoadingMaxRetry: 4,
      fragLoadingTimeOut: 20000, manifestLoadingTimeOut: 12000, levelLoadingTimeOut: 12000
    };
  }

  function videoOpen(it, startMs, gen) {
    var v = $("wopVid"), mine = function () { return gen === P.gen && P.on };
    /* a live picture that waits too long is opened again from the live edge,
       instead of "Buffering…" for ever */
    var stall = 0;
    function unstall() { if (stall) { clearTimeout(stall); stall = 0 } }
    v.onwaiting = function () {
      if (!mine() || P.paused) return;
      status(w("buffering"));
      if (P.live && !stall) stall = setTimeout(function () {
        stall = 0;
        if (mine() && v.readyState < 3) failed("stall");
      }, STALL_MS);
    };
    /* only a picture that really started counts as working again: a <video>
       also reports the time while it is emptied for the next try */
    v.onplaying = function () { unstall(); if (mine()) { status(""); P.tries = 0 } };
    v.ontimeupdate = function () {
      if (!mine()) return;
      P.pos = v.currentTime * 1000; P.dur = isFinite(v.duration) ? v.duration * 1000 : 0;
      drawTime(); savePos(false);
    };
    v.onended = function () { if (mine()) ended() };
    v.onerror = function () { if (mine()) failed(v.error) };
    v.onloadedmetadata = function () { if (mine() && startMs > 0 && !P.live) v.currentTime = startMs / 1000 };
    var t = typeOf(it);
    P.viaHls = false;
    if (t === "video/mp2t" && P.live && isTsUrl(it.u) && HLS_HOSTS[hostOf(it.u)]) {
      var one = {}; for (var k in it) if (it.hasOwnProperty(k)) one[k] = it[k];
      one.u = asHls(it.u);
      it = one; t = "application/x-mpegURL"; P.viaHls = true;
    }
    /* HLS the TV cannot play itself: hls.js */
    if (t === "application/x-mpegURL" && !nativeHls(v) && W.Hls && W.Hls.isSupported()) {
      var h = new W.Hls(hlsConfig(startMs)), recovers = 0;
      ENG = h;
      h.on(W.Hls.Events.ERROR, function (ev, d) {
        if (!mine() || !d) return;
        /* a format this TV has no decoder for (HEVC on many sets): say so,
           and do not go round trying again */
        if (CODEC_ERR[d.details]) { unstall(); failed("hls " + d.details, true); return }
        if (!d.fatal) return;
        if (d.type === W.Hls.ErrorTypes.MEDIA_ERROR && recovers < 2) {
          try { if (recovers++) h.swapAudioCodec(); h.recoverMediaError(); return } catch (x) {}
        }
        unstall();
        failed("hls " + d.details);
      });
      h.loadSource(it.u);
      h.attachMedia(v);
      var hp = v.play(); if (hp && hp.catch) hp.catch(function () {});
      return;
    }
    /* MPEG-TS over Media Source: mpegts.js */
    if (t === "video/mp2t" && mseTs()) {
      var m = W.mpegts.createPlayer({ type: "mpegts", isLive: P.live, url: it.u },
                                    { enableWorker: true, lazyLoad: false, enableStashBuffer: true,
                                      /* a TV has little room for media: drop what was already shown */
                                      autoCleanupSourceBuffer: true, autoCleanupMaxBackwardDuration: 30,
                                      autoCleanupMinBackwardDuration: 15,
                                      /* jumping to the live edge on a slow line makes it stop more */
                                      liveBufferLatencyChasing: false });
      ENG = m;
      m.on(W.mpegts.Events.ERROR, function (type, detail) {
        if (!mine()) return;
        /* a live .ts that is really HLS: go again at once as .m3u8 */
        if (P.live && isTsUrl(it.u) && !HLS_HOSTS[hostOf(it.u)]) {
          HLS_HOSTS[hostOf(it.u)] = true;
          var i = P.i; setTimeout(function () { if (mine()) playAt(i) }, 0);
          return;
        }
        unstall();
        failed("ts " + type + " " + detail, /CodecUnsupported|FormatUnsupported/i.test(String(detail)));
      });
      m.attachMediaElement(v);
      m.load();
      var mp = m.play(); if (mp && mp.catch) mp.catch(function () {});
      return;
    }
    if (t) {
      /* with a <source> a stream that fails says so on the <source>, not the video */
      var src = D.createElement("source"); src.src = it.u; src.type = t;
      src.onerror = function () { if (mine()) failed("source") };
      v.appendChild(src); v.load();
    }
    else v.src = it.u;
    var p = v.play(); if (p && p.catch) p.catch(function () {});
  }

  /* a live channel that drops is picked up again, a little slower each time;
     a film that cannot start says so, and OK tries again */
  function failed(why, codec) {
    /* HLS failed on a server we had moved to HLS: forget it, so the next try
       is the .ts address again */
    if (P.viaHls && !codec) { var cur = P.list[P.i]; if (cur) delete HLS_HOSTS[hostOf(cur.u)]; P.viaHls = false }
    if (P.live && !codec && P.tries < 3) {
      P.tries++;
      status(w("loading"));
      var gen = P.gen, i = P.i;
      setTimeout(function () { if (gen === P.gen && P.on) playAt(i) }, 1200 * P.tries);
      return;
    }
    P.err = true;
    status(w(codec ? "codec" : "fail"), w("again"));
    osd(true);
    try { console.warn("vidaa-player: cannot play", P.list[P.i] && P.list[P.i].u, why) } catch (e) {}
  }

  function ended() {
    if (P.live) { failed("ended"); return }            /* a live stream "ending" is a drop */
    var it = P.list[P.i];
    if (it && W.savePos) { try { W.savePos(it.u, P.dur, P.dur) } catch (e) {} }   /* watched to the end */
    if (P.i + 1 < P.list.length) playAt(P.i + 1); else close();
  }

  function togglePause(force) {
    if (P.live) { osd(true); return }
    var want = force === undefined ? !P.paused : force, v = $("wopVid");
    try {
      if (want) v.pause(); else { var p = v.play(); if (p && p.catch) p.catch(function () {}) }
      P.paused = want;
    } catch (e) {}
    status(P.paused ? w("paused") : "");
    osd(true);
  }

  function seek(ms) {
    if (P.live) { osd(true); return }
    var v = $("wopVid");
    try { v.currentTime = Math.max(0, v.currentTime + ms / 1000) } catch (e) {}
    P.pos = Math.max(0, Math.min(P.dur || Infinity, P.pos + ms));
    drawTime(); osd(true);
  }

  function open(list, index, startMs) {
    if (!list || !list.length) return;
    build();
    P.list = list; P.on = true; P.tries = 0; prevI = -1; NUM.buf = ""; clearTimeout(NUM.t);
    D.documentElement.classList.add("wop-on");
    P.el.className = "on osd";
    playAt(Math.max(0, Math.min(list.length - 1, index | 0)), startMs | 0);
  }

  function close() {
    if (!P.on) return;
    pnClose();
    savePos(true);
    P.on = false;
    stopAll();
    status("");
    if (P.el) P.el.className = "";
    D.documentElement.classList.remove("wop-on");
    /* the highlight goes to the channel that was being watched (after CH+ or
       the number keys that is not the one first opened), else where it was */
    var cur = P.list[P.i], url = cur && cur.u;
    setTimeout(function () {
      try {
        var rows = D.querySelectorAll(".card[data-u]"), hit = null;
        for (var k = 0; k < rows.length && !hit; k++) if (rows[k].getAttribute("data-u") === url) hit = rows[k];
        if (hit && W.navFocus) W.navFocus(hit);
        else if (W.navRestore) W.navRestore();
      } catch (e) {}
    }, 0);
  }

  /* ---------------- the remote ---------------- */
  /* Channels as on the TV itself: ▲ ▼ and CH+ CH- step through the list (a
     channel list goes round at its ends, a series stops), the number keys type
     a channel's number (the one shown as "n / total") and it opens by itself,
     and Back / Recall returns to the channel watched before. */
  var NUM = { buf: "", t: null }, prevI = -1;
  function go(i) {
    if (i === P.i) { osd(true); return }
    prevI = P.i;
    playAt(i);
  }
  function zap(d) {
    var n = P.list.length;
    if (n < 2) { osd(true); return }
    var to = P.i + d;
    if (to < 0 || to >= n) { if (!P.live) { osd(true); return } to = (to + n) % n }
    go(to);
  }
  function lastChannel() {
    if (prevI >= 0 && prevI < P.list.length) go(prevI); else osd(true);
  }
  function typeDigit(d) {
    if (NUM.buf.length >= 4) NUM.buf = "";
    NUM.buf += String(d);
    clearTimeout(NUM.t);
    var it = P.list[(+NUM.buf) - 1];
    $("wopNum").textContent = NUM.buf + " _" + (it && it.n ? "   " + it.n : "");
    osd(true);
    /* as many digits as the list has: the last one opens it at once */
    var enough = NUM.buf.length >= String(P.list.length).length;
    NUM.t = setTimeout(function () {
      var n = +NUM.buf; NUM.buf = "";
      if (!P.on) return;
      if (n >= 1 && n <= P.list.length) go(n - 1); else drawInfo();
    }, enough ? 300 : 1500);
  }
  function digitOf(k) {
    if (k >= 48 && k <= 57) return k - 48;
    if (k >= 96 && k <= 105) return k - 96;
    return -1;
  }

  /* ---------------- the channel list over the picture ----------------
     OK, GUIDE or CH.LIST while a channel plays: the list of channels opens
     beside the picture, which keeps playing. Up / Down choose, CH+ / CH- a
     page, Left / Right the category, OK watches it, Back closes the list. */
  var ALL = [];                       /* every live channel, from setChannels */
  var PN = { on: false, g: 0, i: 0, groups: [], list: [], t: null };
  function pnGroups() {
    var src = ALL.length ? ALL : P.list, vis = src;
    try { if (W.catVisible) vis = W.catVisible(src) } catch (e) {}
    var order = [], seen = {};
    try { if (W.GROUPS && W.GROUPS.length) for (var a = 0; a < W.GROUPS.length; a++) { seen[W.GROUPS[a]] = 1; order.push(W.GROUPS[a]) } } catch (e) {}
    for (var b = 0; b < vis.length; b++) { var g = vis[b].c || ""; if (g && !seen[g]) { seen[g] = 1; order.push(g) } }
    var out = [{ name: w("all"), list: vis }];
    for (var c = 0; c < order.length; c++) {
      var name = order[c], l = [];
      for (var d = 0; d < vis.length; d++) if (vis[d].c === name) l.push(vis[d]);
      if (l.length) out.push({ name: name, list: l });
    }
    return out;
  }
  function pnUse(g, keepU) {
    PN.g = (g + PN.groups.length) % PN.groups.length;
    PN.list = PN.groups[PN.g].list;
    PN.i = 0;
    if (keepU) for (var i = 0; i < PN.list.length; i++) if (PN.list[i].u === keepU) { PN.i = i; break }
  }
  function esc(t) { return String(t == null ? "" : t).replace(/[&<>"]/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c] }) }
  function pnDraw() {
    var box = $("wopPn"); if (!box) return;
    var cur = P.list[P.i], cu = cur && cur.u, n = PN.list.length, rows = 11;
    var from = Math.max(0, Math.min(PN.i - 5, n - rows)), h = "";
    for (var i = from; i < Math.min(n, from + rows); i++) {
      var c = PN.list[i];
      h += '<div class="vxr' + (i === PN.i ? " on" : "") + (c.u === cu ? " now" : "") + '"><b>' + (i + 1) + "</b>" +
        (c.l ? '<img src="' + esc(c.l) + '" referrerpolicy="no-referrer" alt="">' : "<i></i>") + "<em>" + esc(c.n) + "</em></div>";
    }
    box.innerHTML = '<div class="vxg">' + esc(PN.groups[PN.g].name) + "<span>" + (n ? (PN.i + 1) + " / " + n : "") + "</span></div>" +
      '<div class="vxh">' + w("phint") + "</div>" + h;
  }
  function pnAlive() { clearTimeout(PN.t); PN.t = setTimeout(pnClose, 15000) }
  function pnOpen() {
    if (!P.live) return false;
    PN.groups = pnGroups();
    var cur = P.list[P.i], g = 0, cg = cur && cur.c;
    /* the player gets name and address only: its category is in the full list */
    if (cur && !cg) for (var j = 0; j < ALL.length; j++) if (ALL[j].u === cur.u) { cg = ALL[j].c; break }
    if (cg) for (var i = 1; i < PN.groups.length; i++) if (PN.groups[i].name === cg) { g = i; break }
    pnUse(g, cur && cur.u);
    PN.on = true; $("wopPn").className = "on"; osd(false); P.el.classList.add("pn"); pnDraw(); pnAlive();
    return true;
  }
  function pnClose() { PN.on = false; clearTimeout(PN.t); var b = $("wopPn"); if (b) b.className = ""; if (P.el) P.el.classList.remove("pn") }
  function pnKey(k) {
    var rtl = D.documentElement.dir === "rtl", n = PN.list.length;
    if (isBack(k) || k === 458 || k === 10073) pnClose();
    else if (k === K.UP) PN.i = (PN.i - 1 + n) % n;
    else if (k === K.DOWN) PN.i = (PN.i + 1) % n;
    else if (isChUp(k)) PN.i = Math.max(0, PN.i - 10);
    else if (isChDown(k)) PN.i = Math.min(n - 1, PN.i + 10);
    else if (k === K.LEFT || k === K.RIGHT) pnUse(PN.g + (((k === K.RIGHT) !== rtl) ? 1 : -1), (P.list[P.i] || {}).u);
    else if (k === K.ENTER) {
      var pick = PN.list[PN.i]; pnClose();
      if (!pick) return true;
      var cur = P.list[P.i];
      if (cur && cur.u === pick.u) { osd(true); return true }
      /* the chosen category becomes the list CH+ / CH- step through */
      prevI = -1; P.list = PN.list; P.tries = 0; playAt(PN.i);
      return true;
    }
    else return false;
    if (PN.on) { pnDraw(); pnAlive() }
    return true;
  }

  var lastBack = 0;
  D.addEventListener("keydown", function (e) {
    var k = e.keyCode;
    if (P.on && PN.on) {
      if (pnKey(k) || digitOf(k) < 0) { e.preventDefault(); e.stopImmediatePropagation(); return }
    }
    /* OK opens the list while a channel plays (on an error OK tries again);
       GUIDE / CH.LIST open it always, so a channel that will not play can be left */
    if (P.on && P.live && ((k === K.ENTER && !P.err) || k === 458 || k === 10073 || k === 1002) && pnOpen()) {
      e.preventDefault(); e.stopImmediatePropagation(); return;
    }
    if (P.on) {
      var used = true;
      if (isBack(k) || k === K.STOP) close();
      else if (P.err && k === K.ENTER) { P.tries = 0; playAt(P.i) }
      else if (k === K.UP || isChDown(k)) zap(-1);
      else if (k === K.DOWN || isChUp(k)) zap(+1);
      else if (digitOf(k) >= 0) typeDigit(digitOf(k));
      else if (k === K.LEFT || k === K.RW) seek(-10000);
      else if (k === K.RIGHT || k === K.FF) seek(30000);
      else if (k === K.ENTER || isPlayPause(k)) togglePause();
      else if (k === K.PLAY) togglePause(false);
      else if (k === K.PAUSE) togglePause(true);
      else if (k === K.INFO) { if (P.el.classList.contains("osd")) osd(false); else osd(true) }
      else used = false;
      if (used) { e.preventDefault(); e.stopImmediatePropagation() }
      return;
    }
    if (isBack(k)) {
      var ae = D.activeElement;
      /* in a text box Backspace deletes a letter, as it should */
      if (ae && (ae.tagName === "INPUT" || ae.tagName === "TEXTAREA") && (k === 8 || k === 27)) {
        if (k === 27) { e.preventDefault(); ae.blur() }
        return;
      }
      e.preventDefault(); e.stopImmediatePropagation();
      if (ae && ae.tagName === "INPUT") { ae.blur(); return }
      /* something open on the page (a sheet, settings, a film page): close it */
      if (W.LAYERS && W.LAYERS.length) { history.back(); return }
      /* the home screen: twice to leave, so one stray press never closes the app */
      if (Date.now() - lastBack < 2000) { exitApp(); return }
      lastBack = Date.now();
      try { if (W.showToast) W.showToast(w("exit")) } catch (x) {}
    }
  }, true);

  /* the TV went to its home screen or another app: let go of the picture;
     on return a film goes on where it was, a channel starts again */
  D.addEventListener("visibilitychange", function () {
    if (!P.on) return;
    var v = $("wopVid");
    if (D.hidden) { savePos(true); P.hidAt = P.pos; try { v.pause() } catch (e) {} return }
    if (P.live) playAt(P.i);
    else if (!P.paused) playAt(P.i, P.hidAt);
  });

  /* ---------------- what the page finds ---------------- */
  W.AndroidPlayer = {
    isTv: function () { return true },
    version: function () { return "1.0|" + BUILD },
    setLang: function () {},
    codecs: function () { return JSON.stringify({ hevc: true, hevc4k: true, ac3: true, eac3: true }) },
    setChannels: function (json) {
      try { var a = JSON.parse(json); if (a && a.length) ALL = a } catch (e) {}
    },
    /* the page calls this with two arguments or three, as on Android */
    playList: function (json, index, startMs) {
      var list = [];
      try { list = JSON.parse(json) || [] } catch (e) {}
      open(list.filter(function (c) { return c && c.u }), index | 0, startMs | 0);
    },
    playNative: function (u, n) { open([{ u: String(u || ""), n: String(n || "") }], 0, 0) },
    /* a TV app may not start other apps without extra permissions, and
       most links (Telegram, WhatsApp, e-mail) are for a phone anyway: the
       address is shown, so it can be typed there */
    openUrl: function (u) { try { if (W.showToast) W.showToast(String(u)) } catch (e) {} }
  };
  /* for the tests */
  W.KIPTV_VIDAA = { state: function () { return { on: P.on, i: P.i, live: P.live, paused: P.paused, err: P.err, n: P.list.length,
    panel: PN.on, pg: PN.on ? PN.groups[PN.g].name : "", pi: PN.i, pn: PN.list.length, cur: (P.list[P.i] || {}).n || "" } } };
})();
