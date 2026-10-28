/*
 * KURDISTAN IPTV on a Samsung Smart TV (Tizen).
 *
 * The page was written for Android and asks for `AndroidPlayer`; on a TV it
 * is given exactly that, with the same methods and the same arguments, so the
 * page itself does not change (the same way desktop/preload.js does it for
 * Windows). The picture comes from Samsung's own player, webapis.avplay, which
 * plays HLS, MPEG-TS and MP4/MKV in hardware. Without it (a desktop browser,
 * the tests) an ordinary <video> stands in, so everything else can be tried.
 *
 * The remote:
 *   in the app     Back closes what is open; on the home screen, Back twice exits
 *   in the player  Back / Stop  close it
 *                  Up / Down, CH+ / CH-   the channel or episode before / after
 *                  Left / Right, << / >>  films: 10 s back / 30 s on; live: show the bar
 *                  OK, Play/Pause         films: pause and go on; live: show the bar
 *                  Info                   show or hide the bar
 *
 * Not yet on the TV: subtitles (their buttons are hidden), audio-track choice.
 */
(function () {
  "use strict";
  var W = window, D = document;

  /* what build.js tells the page about this build */
  W.KIPTV_DIRECT = true;            /* no app in between: ask servers directly */
  W.KIPTV_NOUPD = true;             /* updates come from the Samsung store */
  var BUILD = String(W.KIPTV_TIZEN_BUILD || "0");
  var VLC_UA = "VLC/3.0.20 LibVLC/3.0.20";   /* what many IPTV servers want to see */

  var K = {
    BACK: 10009, EXIT: 10182, ENTER: 13, UP: 38, DOWN: 40, LEFT: 37, RIGHT: 39,
    PLAYPAUSE: 10252, PLAY: 415, PAUSE: 19, STOP: 413, FF: 417, RW: 412,
    CHUP: 427, CHDOWN: 428, INFO: 457, ESC: 27
  };
  var WANT_KEYS = ["MediaPlayPause", "MediaPlay", "MediaPause", "MediaStop", "MediaFastForward",
                   "MediaRewind", "ChannelUp", "ChannelDown", "Info"];

  function av() { return W.webapis && W.webapis.avplay ? W.webapis.avplay : null }
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

  /* ---------------- the remote's own keys ---------------- */
  function registerKeys() {
    try {
      var have = W.tizen.tvinputdevice.getSupportedKeys().map(function (k) { return k.name });
      WANT_KEYS.forEach(function (n) { if (have.indexOf(n) > -1) W.tizen.tvinputdevice.registerKey(n) });
    } catch (e) { /* not on a TV */ }
  }

  function exitApp() {
    try { W.tizen.application.getCurrentApplication().exit(); return } catch (e) {}
    try { W.close() } catch (e) {}
  }

  /* ---------------- the player's screen ---------------- */
  var P = { on: false, list: [], i: 0, live: true, gen: 0, tries: 0, paused: false,
            dur: 0, pos: 0, osdT: 0, savedAt: 0, err: false, el: null };

  var CSS =
    "html.tzp-on,html.tzp-on body{background:transparent!important}" +
    "html.tzp-on body>*:not(#tzp){visibility:hidden!important}" +
    "#tzp,#tzp *{box-sizing:border-box;margin:0;letter-spacing:normal;text-transform:none}" +
    "#tzp{position:fixed;inset:0;z-index:2147483000;display:none;background:transparent;color:#fff;" +
    "font-family:Arial,'Noto Sans Arabic',sans-serif}" +
    "#tzp.on{display:block}" +
    "#tzpAv,#tzpVid{position:absolute;left:0;top:0;width:100%;height:100%;background:#000}" +
    "#tzp .tzp-bar{position:absolute;left:0;right:0;padding:36px 64px;transition:opacity .25s;opacity:0}" +
    "#tzp.osd .tzp-bar{opacity:1}" +
    "#tzp .tzp-top{top:0;background:linear-gradient(#000c,#0000)}" +
    "#tzp .tzp-bot{bottom:0;background:linear-gradient(#0000,#000c)}" +
    "#tzp .tzp-ttl{font-size:44px;font-weight:700;display:flex;align-items:center;gap:22px}" +
    "#tzp .tzp-live{font-size:22px;font-weight:800;letter-spacing:2px;padding:6px 14px;border-radius:8px;background:#ED2024}" +
    "#tzp .tzp-num{font-size:26px;color:#c9d1dc;margin-top:8px}" +
    "#tzp .tzp-prog{height:8px;border-radius:8px;background:#ffffff33;overflow:hidden}" +
    "#tzp .tzp-prog i{display:block;height:100%;width:0;background:#E8B33C}" +
    "#tzp .tzp-tm{display:flex;justify-content:space-between;font-size:26px;color:#dfe5ec;margin-top:14px}" +
    "#tzp .tzp-st{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);font-size:34px;font-weight:700;" +
    "padding:22px 40px;border-radius:18px;background:#0009;display:none;text-align:center;max-width:70%}" +
    "#tzp .tzp-st.on{display:block}#tzp .tzp-st small{display:block;font-size:24px;font-weight:400;color:#c9d1dc;margin-top:12px}";

  function build() {
    if (P.el) return P.el;
    var s = D.createElement("style"); s.textContent = CSS; D.head.appendChild(s);
    var el = D.createElement("div"); el.id = "tzp";
    el.innerHTML = (av() ? '<object id="tzpAv" type="application/avplayer"></object>' : '<video id="tzpVid" autoplay></video>') +
      '<div class="tzp-bar tzp-top"><div class="tzp-ttl"><span class="tzp-live" id="tzpLive"></span><span id="tzpName"></span></div>' +
      '<div class="tzp-num" id="tzpNum"></div></div>' +
      '<div class="tzp-bar tzp-bot" id="tzpBot"><div class="tzp-prog"><i id="tzpFill"></i></div>' +
      '<div class="tzp-tm"><span id="tzpPos"></span><span id="tzpDur"></span></div></div>' +
      '<div class="tzp-st" id="tzpSt"></div>';
    D.body.appendChild(el);
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
    var st = $("tzpSt"); if (!st) return;
    st.innerHTML = text ? text + (sub ? "<small>" + sub + "</small>" : "") : "";
    st.className = text ? "tzp-st on" : "tzp-st";
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
    $("tzpName").textContent = it.n || "";
    $("tzpLive").textContent = P.live ? w("live") : "";
    $("tzpLive").style.display = P.live ? "" : "none";
    $("tzpNum").textContent = P.list.length > 1 ? (P.i + 1) + " / " + P.list.length : "";
    $("tzpBot").style.visibility = P.live ? "hidden" : "visible";
  }
  function drawTime() {
    if (P.live || !P.el) return;
    $("tzpPos").textContent = fmt(P.pos);
    $("tzpDur").textContent = P.dur ? fmt(P.dur) : "";
    $("tzpFill").style.width = P.dur ? Math.min(100, P.pos / P.dur * 100) + "%" : "0";
  }

  /* a film's place is kept, so it opens where it was left (the page's own store) */
  function savePos(force) {
    var it = P.list[P.i];
    if (!it || P.live || !P.dur || P.pos < 5000) return;
    if (!force && Date.now() - P.savedAt < 10000) return;
    P.savedAt = Date.now();
    try { if (W.savePos) W.savePos(it.u, P.pos, P.dur) } catch (e) {}
  }

  /* ---------------- playing: Samsung's player, or a <video> ---------------- */
  function stopAll() {
    P.gen++;
    var a = av();
    if (a) { try { a.stop() } catch (e) {} try { a.close() } catch (e) {} }
    var v = $("tzpVid");
    if (v) { try { v.pause(); v.removeAttribute("src"); v.load() } catch (e) {} }
  }

  function playAt(i, startMs) {
    if (i < 0 || i >= P.list.length) return;
    savePos(true);
    stopAll();
    var gen = P.gen, it = P.list[i];
    P.i = i; P.live = !it.v; P.paused = false; P.err = false; P.pos = 0; P.dur = 0;
    drawInfo(); drawTime(); osd(true);
    status(w("loading"));
    var a = av();
    if (a) avOpen(a, it, startMs || 0, gen); else videoOpen(it, startMs || 0, gen);
  }

  function avOpen(a, it, startMs, gen) {
    var mine = function () { return gen === P.gen && P.on };
    try {
      a.open(it.u);
      a.setDisplayRect(0, 0, 1920, 1080);
      try { a.setDisplayMethod("PLAYER_DISPLAY_MODE_LETTER_BOX") } catch (e) {}
      try { a.setStreamingProperty("USER_AGENT", it.ua || VLC_UA) } catch (e) {}
      a.setListener({
        onbufferingstart: function () { if (mine() && !P.paused) status(w("buffering")) },
        onbufferingcomplete: function () { if (mine()) status("") },
        oncurrentplaytime: function (ms) {
          if (!mine()) return;
          P.pos = ms; P.tries = 0;
          if (!P.dur) { try { P.dur = a.getDuration() || 0 } catch (e) {} }
          drawTime(); savePos(false);
        },
        onstreamcompleted: function () { if (mine()) ended() },
        onerror: function (type) { if (mine()) failed(type) },
        onevent: function () {}
      });
      a.prepareAsync(function () {
        if (!mine()) return;
        try { P.dur = a.getDuration() || 0 } catch (e) {}
        if (startMs > 0 && !P.live) { try { a.seekTo(startMs) } catch (e) {} }
        try { a.play() } catch (e) { failed(e); return }
        status("");
      }, function (e) { if (mine()) failed(e) });
    } catch (e) { failed(e) }
  }

  function videoOpen(it, startMs, gen) {
    var v = $("tzpVid"), mine = function () { return gen === P.gen && P.on };
    v.onwaiting = function () { if (mine() && !P.paused) status(w("buffering")) };
    v.onplaying = function () { if (mine()) status("") };
    v.ontimeupdate = function () {
      if (!mine()) return;
      P.pos = v.currentTime * 1000; P.dur = isFinite(v.duration) ? v.duration * 1000 : 0; P.tries = 0;
      drawTime(); savePos(false);
    };
    v.onended = function () { if (mine()) ended() };
    v.onerror = function () { if (mine()) failed(v.error) };
    v.onloadedmetadata = function () { if (mine() && startMs > 0 && !P.live) v.currentTime = startMs / 1000 };
    v.src = it.u;
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
    try { console.warn("tizen-player: cannot play", P.list[P.i] && P.list[P.i].u, why) } catch (e) {}
  }

  function ended() {
    if (P.live) { failed("ended"); return }            /* a live stream "ending" is a drop */
    var it = P.list[P.i];
    if (it && W.savePos) { try { W.savePos(it.u, P.dur, P.dur) } catch (e) {} }   /* watched to the end */
    if (P.i + 1 < P.list.length) playAt(P.i + 1); else close();
  }

  function togglePause(force) {
    if (P.live) { osd(true); return }
    var want = force === undefined ? !P.paused : force;
    var a = av(), v = $("tzpVid");
    try {
      if (want) { if (a) a.pause(); else v.pause() }
      else { if (a) a.play(); else v.play() }
      P.paused = want;
    } catch (e) {}
    status(P.paused ? w("paused") : "");
    osd(true);
  }

  function seek(ms) {
    if (P.live) { osd(true); return }
    var a = av(), v = $("tzpVid");
    try {
      if (a) { if (ms > 0) a.jumpForward(ms); else a.jumpBackward(-ms) }
      else v.currentTime = Math.max(0, v.currentTime + ms / 1000);
    } catch (e) {}
    P.pos = Math.max(0, Math.min(P.dur || Infinity, P.pos + ms));
    drawTime(); osd(true);
  }

  function open(list, index, startMs) {
    if (!list || !list.length) return;
    build();
    P.list = list; P.on = true; P.tries = 0;
    D.documentElement.classList.add("tzp-on");
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
    D.documentElement.classList.remove("tzp-on");
    try { if (W.navRestore) setTimeout(W.navRestore, 0) } catch (e) {}
  }

  /* ---------------- the remote ---------------- */
  var lastBack = 0;
  D.addEventListener("keydown", function (e) {
    var k = e.keyCode;
    if (P.on) {
      var used = true;
      if (k === K.BACK || k === K.ESC || k === K.STOP || k === K.EXIT) close();
      else if (P.err && k === K.ENTER) { P.tries = 0; playAt(P.i) }
      else if (k === K.UP || k === K.CHDOWN) { if (P.i > 0) playAt(P.i - 1); else osd(true) }
      else if (k === K.DOWN || k === K.CHUP) { if (P.i + 1 < P.list.length) playAt(P.i + 1); else osd(true) }
      else if (k === K.LEFT || k === K.RW) seek(-10000);
      else if (k === K.RIGHT || k === K.FF) seek(30000);
      else if (k === K.ENTER || k === K.PLAYPAUSE) togglePause();
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

  /* the TV went to its home screen or another app: let go of the picture, and
     take it up again on return */
  D.addEventListener("visibilitychange", function () {
    var a = av();
    if (!P.on || !a) return;
    try { if (D.hidden) a.suspend(); else a.restore() } catch (e) {}
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
    openUrl: function (u) {
      try {
        var ac = new W.tizen.ApplicationControl("http://tizen.org/appcontrol/operation/view", String(u));
        W.tizen.application.launchAppControl(ac, null, function () {}, function () {});
      } catch (e) {}
    }
  };
  /* for the tests */
  W.KIPTV_TIZEN = { state: function () { return { on: P.on, i: P.i, live: P.live, paused: P.paused, err: P.err, n: P.list.length } } };

  registerKeys();
})();
