/*
 * KURDISTAN IPTV on an LG Smart TV (webOS).
 *
 * The same idea as tizen/tizen-player.js: the page was written for Android and
 * asks for `AndroidPlayer`, so on the TV it is given exactly that, with the
 * same methods and arguments, and the page itself does not change. On webOS the
 * picture comes from the TV's own <video>, which LG plays through its hardware
 * media pipeline (HLS, MP4, MKV and MPEG-TS on the models we target).
 *
 * The remote (and the Magic Remote's buttons):
 *   in the app     Back closes what is open; on the home screen, Back twice exits
 *   in the player  Back / Stop  close it
 *                  Up / Down, CH+ / CH-   the channel or episode before / after
 *                  Left / Right, << / >>  films: 10 s back / 30 s on; live: show the bar
 *                  OK, Play/Pause         films: pause and go on; live: show the bar
 *                  Info                   show or hide the bar
 *
 * Not yet on the TV: subtitles (their buttons are hidden), audio-track choice,
 * and a User-Agent of our own (a <video> sends the TV's).
 */
(function () {
  "use strict";
  var W = window, D = document;

  /* what build.js tells the page about this build */
  W.KIPTV_DIRECT = true;            /* no app in between: ask servers directly */
  W.KIPTV_NOUPD = true;             /* updates come from the LG Content Store */
  var BUILD = String(W.KIPTV_WEBOS_BUILD || "0");

  var K = {
    BACK: 461, ENTER: 13, UP: 38, DOWN: 40, LEFT: 37, RIGHT: 39,
    PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, RW: 412,
    CHUP: 33, CHDOWN: 34, INFO: 457, ESC: 27
  };

  function lang() { return (W.lang === "ku" || W.lang === "ar") ? W.lang : "en" }
  var WORDS = {
    en: { loading: "Loading…", buffering: "Buffering…", fail: "This stream cannot be played right now",
          again: "OK: try again · Back: close", live: "LIVE", exit: "Press Back again to exit", paused: "Paused" },
    ku: { loading: "بارکردن…", buffering: "چاوەڕێ بکە…", fail: "ئەم پەخشە ئێستا لێنادرێت",
          again: "OK: دووبارە · Back: داخستن", live: "ڕاستەوخۆ", exit: "بۆ دەرچوون جارێکی تر Back دابگرە", paused: "وەستێنراو" },
    ar: { loading: "جارٍ التحميل…", buffering: "جارٍ التخزين…", fail: "لا يمكن تشغيل هذا البث الآن",
          again: "OK: إعادة · Back: إغلاق", live: "مباشر", exit: "اضغط رجوع مرة أخرى للخروج", paused: "متوقف" }
  };
  function w(k) { return (WORDS[lang()] || WORDS.en)[k] }

  /* Subtitles are not shown by this player yet (Android's player draws them
     natively), so their buttons stay out of sight instead of doing nothing. */
  (function () {
    var st = D.createElement("style");
    st.textContent = "#dSubs,#setBody [onclick*=\"openSheet('subs')\"]{display:none!important}";
    (D.head || D.documentElement).appendChild(st);
  })();

  /* leaving the app the way LG asks: back to the TV's launcher */
  function exitApp() {
    try { if (W.webOSSystem && W.webOSSystem.platformBack) { W.webOSSystem.platformBack(); return } } catch (e) {}
    try { if (W.PalmSystem && W.PalmSystem.platformBack) { W.PalmSystem.platformBack(); return } } catch (e) {}
    try { W.close() } catch (e) {}
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
    "#wop .wop-st.on{display:block}#wop .wop-st small{display:block;font-size:24px;font-weight:400;color:#c9d1dc;margin-top:12px}";

  function build() {
    if (P.el) return P.el;
    var s = D.createElement("style"); s.textContent = CSS; D.head.appendChild(s);
    var el = D.createElement("div"); el.id = "wop";
    el.innerHTML = '<video id="wopVid" autoplay></video>' +
      '<div class="wop-bar wop-top"><div class="wop-ttl"><span class="wop-live" id="wopLive"></span><span id="wopName"></span></div>' +
      '<div class="wop-num" id="wopNum"></div></div>' +
      '<div class="wop-bar wop-bot" id="wopBot"><div class="wop-prog"><i id="wopFill"></i></div>' +
      '<div class="wop-tm"><span id="wopPos"></span><span id="wopDur"></span></div></div>' +
      '<div class="wop-st" id="wopSt"></div>';
    D.body.appendChild(el);
    /* the Magic Remote's pointer: a click on the picture is OK */
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
  function stopAll() {
    P.gen++;
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
    videoOpen(it, startMs || 0, gen);
  }

  /* LG's pipeline picks the format from the type it is told; a live .ts or a
     bare Xtream address is MPEG-TS, an .m3u8 is HLS, the rest it sniffs */
  function typeOf(it) {
    var u = String(it.u).split("?")[0].toLowerCase();
    if (/\.m3u8$/.test(u)) return "application/x-mpegURL";
    if (/\.ts$/.test(u) || (!it.v && !/\.(mp4|mkv|m4v|mov|webm|avi)$/.test(u))) return "video/mp2t";
    return "";
  }

  function videoOpen(it, startMs, gen) {
    var v = $("wopVid"), mine = function () { return gen === P.gen && P.on };
    v.onwaiting = function () { if (mine() && !P.paused) status(w("buffering")) };
    /* only a picture that really started counts as working again: a <video>
       also reports the time while it is emptied for the next try */
    v.onplaying = function () { if (mine()) { status(""); P.tries = 0 } };
    v.ontimeupdate = function () {
      if (!mine()) return;
      P.pos = v.currentTime * 1000; P.dur = isFinite(v.duration) ? v.duration * 1000 : 0;
      drawTime(); savePos(false);
    };
    v.onended = function () { if (mine()) ended() };
    v.onerror = function () { if (mine()) failed(v.error) };
    v.onloadedmetadata = function () { if (mine() && startMs > 0 && !P.live) v.currentTime = startMs / 1000 };
    var t = typeOf(it);
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
  function failed(why) {
    if (P.live && P.tries < 3) {
      P.tries++;
      status(w("loading"));
      var gen = P.gen, i = P.i;
      setTimeout(function () { if (gen === P.gen && P.on) playAt(i) }, 1200 * P.tries);
      return;
    }
    P.err = true;
    status(w("fail"), w("again"));
    osd(true);
    try { console.warn("webos-player: cannot play", P.list[P.i] && P.list[P.i].u, why) } catch (e) {}
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
    P.list = list; P.on = true; P.tries = 0;
    D.documentElement.classList.add("wop-on");
    P.el.className = "on osd";
    playAt(Math.max(0, Math.min(list.length - 1, index | 0)), startMs | 0);
  }

  function close() {
    if (!P.on) return;
    savePos(true);
    P.on = false;
    stopAll();
    status("");
    if (P.el) P.el.className = "";
    D.documentElement.classList.remove("wop-on");
    try { if (W.navRestore) setTimeout(W.navRestore, 0) } catch (e) {}
  }

  /* ---------------- the remote ---------------- */
  var lastBack = 0;
  D.addEventListener("keydown", function (e) {
    var k = e.keyCode;
    if (P.on) {
      var used = true;
      if (k === K.BACK || k === K.ESC || k === K.STOP) close();
      else if (P.err && k === K.ENTER) { P.tries = 0; playAt(P.i) }
      else if (k === K.UP || k === K.CHDOWN) { if (P.i > 0) playAt(P.i - 1); else osd(true) }
      else if (k === K.DOWN || k === K.CHUP) { if (P.i + 1 < P.list.length) playAt(P.i + 1); else osd(true) }
      else if (k === K.LEFT || k === K.RW) seek(-10000);
      else if (k === K.RIGHT || k === K.FF) seek(30000);
      else if (k === K.ENTER) togglePause();
      else if (k === K.PLAY) togglePause(false);
      else if (k === K.PAUSE) togglePause(true);
      else if (k === K.INFO) { if (P.el.classList.contains("osd")) osd(false); else osd(true) }
      else used = false;
      if (used) { e.preventDefault(); e.stopImmediatePropagation() }
      return;
    }
    if (k === K.BACK) {
      e.preventDefault(); e.stopImmediatePropagation();
      var ae = D.activeElement;
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
    setChannels: function () {},
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
  W.KIPTV_WEBOS = { state: function () { return { on: P.on, i: P.i, live: P.live, paused: P.paused, err: P.err, n: P.list.length } } };
})();
