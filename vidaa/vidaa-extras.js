/*
 * KURDISTAN IPTV for VIDAA - fixes and additions for the TV build only.
 *
 * Loaded after the page's own script (vidaa/build.js puts it just before
 * </body>), so it can replace a few of the page's functions for this build
 * alone. Nothing here reaches the Android, Amazon, Windows, Samsung or LG apps.
 *
 *   1. Remote focus: Up / Down go to the nearest row first, so Down from the
 *      top of Settings reaches the first button below instead of jumping to the
 *      bottom; Right on a channel row stays on it instead of leaving the list.
 *   2. M3U parsing: logo and group in every common spelling - tvg-logo in any
 *      letter case, single quotes or none, tvg-logo-small, logo, tvg-icon,
 *      #EXTIMG and #EXTGRP lines - and the guide address (url-tvg).
 *   3. Guide (EPG) for M3U playlists from their XMLTV file (url-tvg), matched
 *      by tvg-id and, failing that, by channel name.
 *   4. Clearer names in the server and speed test.
 *
 * Written for old TV browsers: ES5 only.
 */
(function () {
  "use strict";
  var W = window;
  if (typeof W.navPick !== "function" || typeof W.parseM3U !== "function") return;

  /* ============================ 1. focus ============================ */
  var basePick = W.navPick;
  W.navPick = function (from, dir, items) {
    if (dir !== "up" && dir !== "down") return basePick(from, dir, items);
    var a = navRect(from), ax = a.left + a.width / 2, ay = a.top + a.height / 2;
    var c = [], minMain = Infinity;
    for (var i = 0; i < items.length; i++) {
      var el = items[i];
      if (el === from || el.contains(from)) continue;
      var b = navRect(el), bx = b.left + b.width / 2, by = b.top + b.height / 2;
      if (dir === "down" ? (by <= ay + 1 || b.bottom <= a.bottom) : (by >= ay - 1 || b.top >= a.top)) continue;
      var main = dir === "down" ? Math.max(0, b.top - a.bottom) : Math.max(0, a.top - b.bottom);
      var ov = Math.min(a.right, b.right) - Math.max(a.left, b.left);
      c.push({ el: el, main: main, cross: ov > 0 ? 0 : Math.abs(bx - ax) });
      if (main < minMain) minMain = main;
    }
    /* the nearest row first, then the item in it closest to straight ahead */
    var best = null;
    for (var j = 0; j < c.length; j++) {
      if (c[j].main > minMain + 24) continue;
      if (!best || c[j].cross < best.cross || (c[j].cross === best.cross && c[j].main < best.main)) best = c[j];
    }
    return best ? best.el : null;
  };

  /* Right on a channel row (Left in Kurdish and Arabic): there is nothing
     beside it - stay, instead of jumping up to the search box */
  W.addEventListener("keydown", function (e) {
    var ae = document.activeElement;
    if (!ae || !ae.classList || !ae.classList.contains("tvrow")) return;
    var rtl = (t().dir === "rtl");
    if ((e.key === "ArrowRight" && !rtl) || (e.key === "ArrowLeft" && rtl)) {
      e.preventDefault();
      e.stopImmediatePropagation();
    }
  }, true);

  /* A page that is drawn again (Settings when the speed test or the account
     check finishes, a sheet, the channel list after a refresh) used to drop
     the highlight: the item it was on no longer existed, so the remote did
     nothing until it was pressed again, and then started from the top.
     Remember the item before the page is drawn and put the highlight back on
     the same item after. */
  function focusKey(el) {
    if (!el || el === document.body || !el.getAttribute) return null;
    if (!document.documentElement.classList.contains("kbd")) return null;
    return {
      u: el.getAttribute("data-u") || "",
      oc: el.getAttribute("onclick") || "",
      tx: (el.textContent || "").replace(/\s+/g, " ").trim().slice(0, 60),
      cl: (el.className || "").toString().split(" ")[0],
      top: el.getBoundingClientRect().top
    };
  }
  function focusBack(k) {
    if (!k) return;
    var ae = document.activeElement;
    if (ae && ae !== document.body && document.contains(ae)) return;   /* it survived */
    var items = navItems(), best = null, bestScore = -1;
    for (var i = 0; i < items.length; i++) {
      var el = items[i], s = 0;
      if (k.u && el.getAttribute("data-u") === k.u) s += 8;
      if (k.oc && el.getAttribute("onclick") === k.oc) s += 4;
      if (k.tx && (el.textContent || "").replace(/\s+/g, " ").trim().slice(0, 60) === k.tx) s += 2;
      if (k.cl && (el.className || "").toString().split(" ")[0] === k.cl) s += 1;
      if (s > bestScore || (s === bestScore && best &&
          Math.abs(el.getBoundingClientRect().top - k.top) < Math.abs(best.getBoundingClientRect().top - k.top))) {
        best = el; bestScore = s;
      }
    }
    if (best && bestScore >= 1) navFocus(best);
  }
  ["refreshSettings", "hcDraw", "drawSheet", "render", "renderChips"].forEach(function (name) {
    var fn = W[name];
    if (typeof fn !== "function") return;
    W[name] = function () {
      var k = focusKey(document.activeElement);
      var r = fn.apply(this, arguments);
      if (k) { focusBack(k); setTimeout(function () { focusBack(k) }, 0) }
      return r;
    };
  });

  /* ======================== 2. M3U parsing ========================= */
  /* an attribute however it is written: any letter case, "x", 'x' or x */
  W.lastAttr = function (L, name) {
    var re = new RegExp("(?:^|[\\s,])" + name.replace(/[-\/\\^$*+?.()|[\]{}]/g, "\\$&") +
      "\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s,\"']+))", "gi");
    var m, v = "";
    while ((m = re.exec(L))) v = m[1] != null ? m[1] : (m[2] != null ? m[2] : (m[3] || ""));
    return v;
  };
  function attr1(L, names) {
    for (var i = 0; i < names.length; i++) { var v = W.lastAttr(L, names[i]); if (v) return v }
    return "";
  }
  var LOGO = ["tvg-logo", "tvg-logo-small", "logo", "tvg-icon", "logo-url", "logo_url"];

  var KX = W.KIPTV_VIDAA_X = { xmltv: [], idx: null, loading: null, at: 0, byU: null, byUFor: null, sidKey: {} };

  var baseParse = W.parseM3U;
  W.parseM3U = function (txt) {
    txt = String(txt || "");
    var lines = txt.split(/\r?\n/), info = {}, guide = [], pend = null, i, L;
    for (i = 0; i < lines.length; i++) {
      L = lines[i];
      if (!L) continue;
      if (L.charAt(0) === "#") {
        if (L.slice(0, 7) === "#EXTM3U") {
          var g = attr1(L, ["url-tvg", "x-tvg-url", "tvg-url"]);
          if (g) g.split(",").forEach(function (u) { u = u.trim(); if (/^https?:\/\//i.test(u) && guide.indexOf(u) < 0) guide.push(u) });
        } else if (L.slice(0, 7) === "#EXTINF") {
          pend = { id: W.lastAttr(L, "tvg-id"), nm: W.lastAttr(L, "tvg-name") || L.slice(L.lastIndexOf(",") + 1).trim(),
                   logo: attr1(L, LOGO), grp: W.lastAttr(L, "group-title") };
        } else if (pend && L.slice(0, 8) === "#EXTIMG:") {
          if (!pend.logo) pend.logo = L.slice(8).trim();
        } else if (pend && L.slice(0, 8) === "#EXTGRP:") {
          if (!pend.grp) pend.xgrp = L.slice(8).trim();
        }
      } else if (pend) {
        var u = L.trim(), bar = u.indexOf("|");
        if (bar > 0) u = u.slice(0, bar);
        if (u) { info[u] = pend; pend = null }
      }
    }
    var out = baseParse(txt);
    for (i = 0; i < out.length; i++) {
      var c = out[i], x = info[c.u];
      if (!x) continue;
      if (!c.l && x.logo) c.l = artURL(x.logo);
      if (x.xgrp && (!c.c || c.c === "Other")) c.c = x.xgrp;
      if (x.id) c.tv = x.id;
      if (x.nm) c.tn = x.nm;
    }
    KX.xmltv = guide;
    KX.idx = null; KX.loading = null; KX.byU = null;
    try { var p = activeList(); if (p) localStorage.setItem("kiptv_vx_" + p.id, JSON.stringify(guide)) } catch (e) {}
    return out;
  };

  /* ====================== 3. guide from XMLTV ====================== */
  function guideUrls() {
    if (KX.xmltv.length) return KX.xmltv;
    try {
      var p = activeList();
      var g = p && JSON.parse(localStorage.getItem("kiptv_vx_" + p.id) || "[]");
      if (g && g.length) KX.xmltv = g;
    } catch (e) {}
    return KX.xmltv;
  }
  function isM3u() { var p = activeList(); return !!(p && p.type === "m3u") }
  function nameKey(s) {
    return String(s || "").toLowerCase()
      .replace(/\b(fhd|uhd|hd|sd|4k|hevc|h265|backup|\+1)\b/g, "")
      .replace(/[^a-z0-9\u0600-\u06ff]+/g, "");
  }

  var baseOn = W.epgOn, baseSid = W.epgSid, baseAsk = W.epgAsk;
  W.epgOn = function () { return baseOn() || (isM3u() && guideUrls().length > 0) };
  W.epgSid = function (u) {
    if (!isM3u()) return baseSid(u);
    if (KX.byUFor !== CH) {
      KX.byU = {}; KX.byUFor = CH;
      for (var i = 0; i < CH.length; i++) if (CH[i] && CH[i].u) KX.byU[CH[i].u] = CH[i];
    }
    var c = KX.byU[u];
    if (!c) return "";
    var id = c.tv || "", nk = nameKey(c.tn || c.n);
    if (!id && !nk) return "";
    var sid = "t" + (id ? "i" + id : "n" + nk).replace(/[^A-Za-z0-9._-]/g, "_");
    KX.sidKey[sid] = { id: id, nk: nk };
    return sid;
  };
  W.epgAsk = function (sid) {
    if (String(sid).charAt(0) !== "t") return baseAsk(sid);
    var done = function (v) { try { epgSave(sid, v) } catch (e) {} epgBusy--; epgNext() };
    xmlIndex().then(function (ix) { done(ix ? xmlPick(ix, KX.sidKey[sid]) : null) }, function () { done(null) });
  };

  /* "20261113043000 +0300" -> ms */
  function xmlTime(s) {
    var m = String(s || "").match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*([+-]\d{4})?/);
    if (!m) return 0;
    var ms = Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0));
    if (m[7]) { var sg = m[7].charAt(0) === "-" ? -1 : 1; ms -= sg * ((+m[7].slice(1, 3)) * 60 + (+m[7].slice(3, 5))) * 60000 }
    return ms;
  }
  function xmlText(s) {
    return String(s || "").replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, "$1")
      .replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;|&apos;/g, "'")
      .replace(/&#(\d+);/g, function (a, n) { return String.fromCharCode(+n) }).replace(/&amp;/g, "&").trim();
  }
  /* the file as text; a .gz file is unpacked where the TV can */
  function xmlFetch(u) {
    return fetch(viaApp(u)).then(function (r) {
      if (!r.ok) throw new Error("HTTP " + r.status);
      return r.arrayBuffer();
    }).then(function (buf) {
      var b = new Uint8Array(buf);
      if (b.length > 2 && b[0] === 0x1f && b[1] === 0x8b) {
        if (!W.DecompressionStream || !W.Response || !W.Blob || !Blob.prototype.stream) throw new Error("NO GZIP");
        return new Response(new Blob([b]).stream().pipeThrough(new DecompressionStream("gzip"))).text();
      }
      return new TextDecoder("utf-8").decode(b);
    });
  }
  /* only what is needed: the channels in the list, from six hours ago to a day ahead */
  function xmlBuild(txt) {
    var want = {}, names = {}, ix = { byId: {}, byName: {} }, i;
    for (i = 0; i < CH.length; i++) { var c = CH[i]; if (!c) continue; if (c.tv) want[c.tv] = 1; names[nameKey(c.tn || c.n)] = 1 }
    var re = /<channel\b[^>]*\bid\s*=\s*"([^"]*)"[^>]*>([\s\S]*?)<\/channel>/g, m;
    var idOfName = {};
    while ((m = re.exec(txt))) {
      var dn = /<display-name[^>]*>([\s\S]*?)<\/display-name>/g, d;
      while ((d = dn.exec(m[2]))) { var k = nameKey(xmlText(d[1])); if (k && names[k] && !idOfName[k]) idOfName[k] = m[1] }
    }
    for (var nk0 in idOfName) want[idOfName[nk0]] = 1;
    var now = Date.now(), from = now - 6 * 3600000, to = now + 24 * 3600000;
    var pr = /<programme\b([^>]*)>([\s\S]*?)<\/programme>/g;
    for (var key in idOfName) ix.byName[key] = idOfName[key];
    /* a big guide is read a slice at a time, so the remote never freezes */
    return new Promise(function (done) {
      var slice = function () {
        var n = 0;
        while (n++ < 4000 && (m = pr.exec(txt))) take(m);
        if (m) setTimeout(slice, 0); else done(ix);
      };
      slice();
    });
    function take(m) {
      var at = m[1];
      var ch = (at.match(/\bchannel\s*=\s*"([^"]*)"/) || [])[1];
      if (!ch || !want[ch]) return;
      var s = xmlTime((at.match(/\bstart\s*=\s*"([^"]*)"/) || [])[1]), e = xmlTime((at.match(/\bstop\s*=\s*"([^"]*)"/) || [])[1]);
      if (!s || (e && e < from) || s > to) return;
      var tt = m[2].match(/<title[^>]*>([\s\S]*?)<\/title>/);
      if (!tt) return;
      (ix.byId[ch] = ix.byId[ch] || []).push({ s: s, e: e, t: xmlText(tt[1]) });
    }
  }
  function xmlIndex() {
    if (KX.idx && Date.now() - KX.at < 6 * 3600000) return Promise.resolve(KX.idx);
    if (KX.loading) return KX.loading;
    var urls = guideUrls().slice(0, 3);
    if (!urls.length) return Promise.resolve(null);
    var merged = { byId: {}, byName: {} };
    var step = function (n) {
      if (n >= urls.length) return Promise.resolve(merged);
      return xmlFetch(urls[n]).then(function (txt) {
        /* a TV has little memory: a guide bigger than this is left out */
        if (txt.length > 60000000) throw new Error("TOO BIG");
        return xmlBuild(txt);
      }).then(function (ix) {
        var k;
        for (k in ix.byId) if (!merged.byId[k]) merged.byId[k] = ix.byId[k];
        for (k in ix.byName) if (!merged.byName[k]) merged.byName[k] = ix.byName[k];
      }).catch(function (e) { try { console.warn("vidaa-extras: guide", urls[n], e && e.message) } catch (x) {} })
        .then(function () { return step(n + 1) });
    };
    KX.loading = step(0).then(function (ix) { KX.idx = ix; KX.at = Date.now(); KX.loading = null; return ix });
    return KX.loading;
  }
  function xmlPick(ix, key) {
    if (!key) return null;
    var list = (key.id && ix.byId[key.id]) || (key.nk && ix.byName[key.nk] && ix.byId[ix.byName[key.nk]]) || null;
    if (!list || !list.length) return null;
    var now = Date.now(), cur = null, nxt = null;
    for (var i = 0; i < list.length; i++) {
      var p = list[i];
      if (p.s <= now && (!p.e || now < p.e)) { if (!cur || p.s > cur.s) cur = p }
      else if (p.s > now && (!nxt || p.s < nxt.s)) nxt = p;
    }
    var use = cur || nxt;
    return use ? { t: use.t, s: use.s, e: use.e, at: Date.now() } : null;
  }

  /* ================= 5. http:// that the TV cannot reach ================= */
  /* Someone types http://panel when the panel lives at https://panel. The
     panel answers http with a redirect that carries no CORS header, and an
     https page may not ask http at all - so in a TV browser nothing answers,
     though the same account works on a phone. Before such a list loads, ask
     the http address once; if it does not answer and the https one does, the
     list is kept with https from then on. An http panel that answers is left
     exactly as it is. */
  function fieldOf(p) { return p.type === "xtream" ? "domain" : p.type === "mac" ? "portal" : p.type === "m3u" ? "url" : "" }
  function probeOf(p, base) {
    if (p.type === "xtream")
      return base.replace(/\/+$/, "") + "/player_api.php?username=" + encodeURIComponent(p.user || "") + "&password=" + encodeURIComponent(p.pass || "");
    if (p.type === "mac")
      return stkRoot(base) + "/portal.php?type=stb&action=handshake&token=&prehash=0&JsHttpRequest=1-xml&mac=" + encodeURIComponent(macNorm(p.mac));
    return base;
  }
  function answers(u) {
    return fetch(u).then(function (r) {
      try { if (r.body && r.body.cancel) r.body.cancel() } catch (e) {}
      return r.status < 500;
    }, function () { return false });
  }
  var baseLoad = W.loadActive, upTried = {};
  W.loadActive = function () {
    var self = this, args = arguments, p = activeList(), f = p && fieldOf(p), v = f ? String(p[f] || "") : "";
    if (!f || !/^http:\/\//i.test(v) || upTried[p.id + "|" + v]) return baseLoad.apply(self, args);
    upTried[p.id + "|" + v] = 1;
    var sec = "https://" + v.slice(7);
    answers(probeOf(p, v)).then(function (ok) {
      return ok ? false : answers(probeOf(p, sec));
    }).then(function (fix) {
      if (!fix) return;
      var a = getLists();
      for (var i = 0; i < a.length; i++) if (a[i].id === p.id) a[i][f] = sec;
      saveLists(a);
      try { console.warn("vidaa-extras: " + v + " -> " + sec) } catch (e) {}
    }).then(function () { baseLoad.apply(self, args) }, function () { baseLoad.apply(self, args) });
  };
  /* the page loaded its list before this file: look at that one now */
  try {
    var p0 = activeList(), f0 = p0 && fieldOf(p0);
    if (f0 && /^http:\/\//i.test(String(p0[f0] || ""))) W.loadActive(true);
  } catch (e) {}

  /* ============== 6. the rest of the remote, on the channel list ============== */
  /* What a Hisense remote has beyond the arrows, used where the list is open
     (the player already takes CH, numbers, INFO, Play/Pause and Back):
       red     add / remove the highlighted channel from Favourites
       green   the categories          yellow  search
       blue    Settings                GUIDE / CH.LIST  back to the channel list
       CH+ / CH-  a page of channels down / up
       0-9     type a channel number: that channel is highlighted and opens */
  var RK = {
    red: [403, 112], green: [404, 113], yellow: [405, 114], blue: [406, 115],
    guide: [458, 10070], chlist: [10073, 1002]
  };
  var RN = { red: /^(ColorF0Red|Red)$/, green: /^(ColorF1Green|Green)$/, yellow: /^(ColorF2Yellow|Yellow)$/,
             blue: /^(ColorF3Blue|Blue)$/, guide: /^Guide$/, chlist: /^(ChannelList|LiveContent)$/ };
  function which(e) {
    for (var k in RK) if (RK[k].indexOf(e.keyCode) > -1 || (e.key && RN[k].test(e.key))) return k;
    return "";
  }
  function playing() { try { return !!(W.KIPTV_VIDAA && KIPTV_VIDAA.state().on) } catch (e) { return false } }
  function typing() { var a = document.activeElement; return !!(a && (a.tagName === "INPUT" || a.tagName === "TEXTAREA")) }
  function rows() { return document.querySelectorAll(".tvrow") }
  function focusRow(r) { if (r) navFocus(r) }
  function curRow() {
    var a = document.activeElement;
    return a && a.classList && a.classList.contains("tvrow") ? a : null;
  }
  function listBack() {
    var go = function () { var r = rows(), c = curRow(); if (!c) focusRow(r.length ? r[0] : null) };
    if (W.LAYERS && LAYERS.length) { try { layerBack(LAYERS.length) } catch (e) {} setTimeout(go, 120) }
    else go();
  }
  var NUMS = { buf: "", t: null };
  function numKey(d) {
    NUMS.buf = (NUMS.buf + d).slice(0, 5);
    try { showToast(NUMS.buf) } catch (e) {}
    clearTimeout(NUMS.t);
    NUMS.t = setTimeout(function () {
      var n = parseInt(NUMS.buf, 10); NUMS.buf = "";
      var r = rows();
      /* rows are drawn a page at a time: ask for more until the number is there */
      var tries = 0, find = function () {
        r = rows();
        if (n >= 1 && n <= r.length) { focusRow(r[n - 1]); r[n - 1].click(); return }
        if (tries++ < 20 && typeof tvMore === "function" && r.length < (view ? view.length : 0)) { try { tvMore() } catch (e) {} setTimeout(find, 60) }
      };
      find();
    }, 1200);
  }
  W.addEventListener("keydown", function (e) {
    if (playing() || typing()) return;
    if (W.LAYERS && LAYERS.indexOf && LAYERS.indexOf("settings") > -1 && !which(e)) return;
    var k = e.keyCode, act = which(e), done = true;
    if (act === "red") { var c = curRow(); if (c) longPress(c); else done = false }
    else if (act === "green") { var oc = document.querySelector(".tvcat.on") || document.querySelector(".tvcat"); if (oc) navFocus(oc); else done = false }
    else if (act === "yellow") { var s = document.querySelector(".tvsrch"); if (s) s.click(); else if (typeof toggleSearch === "function") toggleSearch() }
    else if (act === "blue") openSettings();
    else if (act === "guide" || act === "chlist") listBack();
    else if ((k === 33 || k === 427 || k === 34 || k === 428) && curRow()) {
      var r = rows(), i = Array.prototype.indexOf.call(r, curRow()), step = 8;
      var to = (k === 33 || k === 427) ? i - step : i + step;
      if (to >= r.length && typeof tvMore === "function") { try { tvMore() } catch (x) {} r = rows() }
      focusRow(r[Math.max(0, Math.min(r.length - 1, to))]);
    }
    else if (((k >= 48 && k <= 57) || (k >= 96 && k <= 105)) && rows().length) numKey(k >= 96 ? k - 96 : k - 48);
    else done = false;
    if (done) { e.preventDefault(); e.stopImmediatePropagation() }
  }, true);

  /* a small key guide under the list, so the colours explain themselves */
  var HINT = {
    en: ["Favourite", "Categories", "Search", "Settings", "0-9 channel number"],
    ku: ["دڵخواز", "پۆلەکان", "گەڕان", "ڕێکخستن", "0-9 ژمارەی کەناڵ"],
    ar: ["المفضلة", "الفئات", "بحث", "الإعدادات", "0-9 رقم القناة"]
  };
  try {
    var css = document.createElement("style");
    css.textContent = "#vxHint{position:fixed;left:50%;bottom:10px;transform:translateX(-50%);z-index:40;display:flex;gap:22px;" +
      "padding:6px 16px;border-radius:999px;background:rgba(8,12,20,.78);color:#cbd5e1;font:600 15px/1.2 system-ui,sans-serif;pointer-events:none}" +
      "#vxHint i{display:inline-block;width:14px;height:14px;border-radius:4px;margin:0 6px;vertical-align:-2px}" +
      "html.wop-on #vxHint,html:not(.tv) #vxHint{display:none}";
    document.head.appendChild(css);
    var hint = document.createElement("div");
    hint.id = "vxHint";
    var drawHint = function () {
      var h = HINT[t().dir === "rtl" ? (lang === "ar" ? "ar" : "ku") : "en"] || HINT.en;
      hint.innerHTML = '<span><i style="background:#e5484d"></i>' + h[0] + '</span><span><i style="background:#30a46c"></i>' + h[1] +
        '</span><span><i style="background:#f5d90a"></i>' + h[2] + '</span><span><i style="background:#3e63dd"></i>' + h[3] + '</span><span>' + h[4] + '</span>';
      hint.style.display = (W.LAYERS && LAYERS.length) ? "none" : "";
    };
    document.body.appendChild(hint);
    drawHint();
    W.addEventListener("popstate", function () { setTimeout(drawHint, 0) });
    var baseOpenS = W.openSettings;
    W.openSettings = function () { var r = baseOpenS.apply(this, arguments); drawHint(); return r };
    var baseApply = W.applyLang;
    if (typeof baseApply === "function") W.applyLang = function () { var r = baseApply.apply(this, arguments); drawHint(); return r };
  } catch (e) {}

  /* ======================= 4. clearer names ======================= */
  var NAMES = {
    en: { hNet: "Internet speed (Cloudflare)", hProv: "Your playlist's server", hLive: "Channel stream" },
    ku: { hNet: "خێرایی ئینتەرنێت (Cloudflare)", hProv: "سێرڤەری لیستەکەت", hLive: "پەخشی کەناڵ" },
    ar: { hNet: "سرعة الإنترنت (Cloudflare)", hProv: "خادم قائمتك", hLive: "بث القناة" }
  };
  try {
    for (var lg in NAMES) if (T[lg]) for (var k in NAMES[lg]) T[lg][k] = NAMES[lg][k];
  } catch (e) {}
})();
