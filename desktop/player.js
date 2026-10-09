/*
 * Playback on Windows.
 *
 * The Android app plays with ExoPlayer and the FFmpeg extensions beside it,
 * which is why it copes with whatever a panel sends: MPEG-TS over HTTP, HEVC,
 * AC3 sound, Matroska films. A browser does none of that, so the desktop app
 * carries mpv - the same FFmpeg underneath - and hands it the same queue the
 * page hands the Android player.
 *
 * mpv is embedded in the app's own window, so it looks like one program, and
 * it is spoken to over a pipe: position, pause, stop, next and previous.
 */
const { spawn } = require("child_process");
const fs = require("fs");
const net = require("net");
const os = require("os");
const path = require("path");

const PIPE = process.platform === "win32"
  ? "\\\\.\\pipe\\kiptv-mpv"
  : path.join(os.tmpdir(), "kiptv-mpv.sock");

/* Every mpv gets a pipe of its own. Two of them share a name only long
   enough to go wrong: the one shutting down takes the name away from the one
   starting, and the new one is then deaf - nothing can be asked of it,
   including which channel it ended up on. */
let pipeNo = 0;
function newPipe() {
  const tag = process.pid + "-" + (++pipeNo);
  return process.platform === "win32"
    ? "\\\\.\\pipe\\kiptv-mpv-" + tag
    : path.join(os.tmpdir(), "kiptv-mpv-" + tag + ".sock");
}

/* ---------------------------------------------------------------- the queue */

/**
 * mpv is given a playlist file rather than a command line full of addresses:
 * Windows stops a command line at 32767 characters, and a panel's channel
 * list goes well past that on its own.
 */
/** a film or an episode, as opposed to a channel going out live */
function isVod(u) {
  const s = String(u || "").toLowerCase().split("?")[0];
  return /\/(movie|movies|series|vod)\//.test(s) ||
         /\.(mp4|mkv|avi|mov|webm|flv|m4v|m4a)$/.test(s);
}

/**
 * Films and episodes are asked for through FFmpeg's own web code rather than
 * the player's, because only FFmpeg can be told to keep one connection open
 * across the jumps such a file needs. A badly made film - one whose sound is
 * stored at the far end of the file, away from its picture - makes hundreds
 * of those jumps, and each one that opens a new connection costs a fifth of a
 * second. Live channels are left alone: they never jump, and what carries
 * them now works.
 */
/**
 * Three ways to write the same queue, and one last resort:
 *
 *   "proxy"  channels fetched through the app's own go-between, which follows
 *            wherever the panel sends it and writes the stream's parts out in
 *            full - the only way a channel whose panel moves it twice can be
 *            found at all. Films and episodes plain, as always.
 *   "auto"   everything the way mpv opens it itself
 *   "plain"  the same thing; kept because older code says it
 *   "all"    everything through FFmpeg - a last resort, never the first try
 *
 * A word on FFmpeg and films. Step 24 marked films "lavf://" to have them
 * fetched by FFmpeg's own web code; mpv quietly refuses such a mark in a
 * playlist it is not told to trust, so for weeks the mark did nothing and
 * films played the ordinary way. Step 27 added that permission for another
 * reason, and the long-dormant mark came to life - and films stopped
 * playing. So it is gone from the first attempt: what opens a film now is
 * exactly what opened it before any of this.
 */
/** a stream that is a list of pieces (HLS), whether a channel, a film or an episode */
function isHls(u) {
  return /\.m3u8?$/.test(String(u || "").toLowerCase().split("?")[0]);
}

/**
 * Whether the go-between carries this one on the first try.
 *
 * Channels always did. A film or an episode that is a list of pieces now
 * does too: its server answers by sending the player somewhere else, and the
 * list found there names its pieces in short form - exactly the trap channels
 * fell into, and the reason films and series played on the phone but not
 * here. A film that is one plain file still goes straight to mpv.
 */
function viaFirst(u, vod) { return isHls(u) || !(vod || isVod(u)) }

/** where the go-between's piece-by-piece door is, beside its list door */
function segOf(via) { return via ? via.replace(/\/live\?u=$/, "/seg?u=") : null }

/*
 *   "robust" everything through the go-between: lists through its list door,
 *            a film's file through its piece door - asked the way its server
 *            answers (another agent, secure DNS, an old certificate)
 */
function playlistText(items, mode, via) {
  /* it used to be a flag; both spellings still mean the same thing */
  const how = (mode === true) ? "auto" : (mode === false || !mode) ? "plain" : mode;
  const seg = segOf(via);
  let out = "#EXTM3U\n";
  for (const it of items) {
    if (!it || !it.u) continue;
    const web = /^https?:/i.test(it.u);
    let line = it.u;
    if (web && how === "all") line = "lavf://" + it.u;
    else if (web && how === "proxy" && via && viaFirst(it.u, it.v)) line = via + encodeURIComponent(it.u);
    else if (web && how === "robust" && via)
      line = (viaFirst(it.u, it.v) || !seg ? via : seg) + encodeURIComponent(it.u);
    out += "#EXTINF:-1," + String(it.n || "").replace(/[\r\n]+/g, " ") + "\n";
    out += line + "\n";
  }
  return out;
}

const QUEUE_FILE = {
  proxy: "kiptv-via.m3u8", robust: "kiptv-robust.m3u8", auto: "kiptv-queue.m3u8",
  plain: "kiptv-plain.m3u8", all: "kiptv-lavf.m3u8"
};

function writePlaylist(items, dir, mode, via) {
  const how = (mode === true) ? "auto" : (mode === false || !mode) ? "plain" : mode;
  const file = path.join(dir, QUEUE_FILE[how] || QUEUE_FILE.plain);
  fs.writeFileSync(file, playlistText(items, how, via), "utf8");
  return file;
}

/** the address itself: without the "play this through FFmpeg" mark, and
 *  taken back out of the go-between's address when it went through there */
function bareUrl(u) {
  let s = String(u || "");
  if (s.startsWith("lavf://")) s = s.slice(7);
  const m = s.match(/^http:\/\/127\.0\.0\.1:\d+\/(?:live|seg)\?u=(.*)$/);
  if (m) { try { s = decodeURIComponent(m[1]) } catch (e) { } }
  return s;
}

function sameStream(a, b) { return bareUrl(a) === bareUrl(b); }

const wait = ms => new Promise(r => setTimeout(r, ms));

/* ------------------------------------------------------------- the arguments */

/**
 * Everything mpv is told, in one place so it can be read and checked.
 *
 * mpv gets a window of its own rather than drawing inside the app's. Drawing
 * inside was tried and gives sound without a picture: Electron keeps its own
 * window for the page in front of everything else in that window, and paints
 * it solid, so the film plays behind a black sheet. Its own window, opened
 * over the app's and the same size, is what the person actually wanted.
 */
function mpvArgs(opts) {
  const it = (opts.items || [])[opts.index || 0] || {};
  const a = [
    "--playlist=" + opts.playlist,
    "--playlist-start=" + (opts.index || 0),
    "--force-window=yes",
    "--idle=no",
    "--keep-open=no",
    "--osc=yes",
    "--osd-bar=yes",
    "--input-default-bindings=yes",
    "--input-ipc-server=" + (opts.pipe || PIPE),
    /* The queue is written by this app, from addresses the app itself built.
       mpv will not follow a line in a playlist that names how to open it
       unless it is told the list can be trusted - and without that, asking
       for a film or a channel to go through FFmpeg is quietly ignored. */
    "--load-unsafe-playlists",
    /* there is no youtube-dl here to ask; without this mpv spends half a
       second looking for one every time a channel fails */
    "--ytdl=no",
    /* the name of whatever is playing, which the queue file carries */
    "--title=${media-title}",
    "--hwdec=auto-safe",
    "--force-seekable=yes",
    /* Room to ride out a bump.
       An episode of a series runs to two gigabytes for forty minutes - seven
       or eight megabits every second, far more than a television channel asks
       for. Eight seconds of it is barely seven megabytes, so the smallest
       hesitation on the line emptied the buffer and the picture stopped. A
       minute of reading ahead costs nothing on a live channel, which has no
       future to send anyway, and it is what carries a heavy film or episode
       over a slow patch without stopping. */
    "--cache=yes",
    "--cache-secs=60",
    "--demuxer-max-bytes=192MiB",
    "--demuxer-max-back-bytes=48MiB",
    "--demuxer-readahead-secs=60",
    /* one connection held open rather than a new request for every read, which
       is what a server charges most dearly for on a large file */
    "--stream-lavf-o-append=multiple_requests=1",

    /* Start as the phone starts.
       ffmpeg studies a stream before playing it, and for MPEG-TS it studies
       five seconds of it by default - which is exactly five seconds of staring
       at nothing on every channel. ExoPlayer on the phone begins almost at
       once. One second of study, and a megabyte or two, is enough to find the
       picture and the sound on a television channel. */
    "--demuxer-lavf-analyzeduration=1",
    "--demuxer-lavf-probesize=2000000",

    /* A line that drops is put back up.
       This is what the phone does with reconnects of its own, and its absence
       here is why a film or an episode simply stopped part way through. */
    "--stream-lavf-o-append=reconnect=1",
    "--stream-lavf-o-append=reconnect_streamed=1",
    "--stream-lavf-o-append=reconnect_on_network_error=1",
    "--stream-lavf-o-append=reconnect_delay_max=7",

    /* A channel that has gone away must say so rather than be waited on for
       ever - with the reconnects above, a slow answer is still retried. */
    "--network-timeout=20",

    /* a playlist served over plain http whose pieces are https is ordinary
       enough, and is refused unless both are allowed */
    /* httpproxy: a machine that reaches the internet through a proxy */
    "--stream-lavf-o-append=protocol_whitelist=file,http,https,tcp,tls,crypto,hls,applehttp,httpproxy,data",
    "--demuxer-lavf-o-append=protocol_whitelist=file,http,https,tcp,tls,crypto,hls,applehttp,httpproxy,data",

    /* the panel is spoken to the way the Android app speaks to it */
    "--user-agent=" + (it.ua || opts.ua || "VLC/3.0.20 LibVLC/3.0.20"),
    "--http-header-fields-append=Accept: */*",
    "--http-header-fields-append=Accept-Language: en-US,en;q=0.9"
  ];
  if (it.rf) a.push("--http-header-fields-append=Referer: " + it.rf);
  /* when a channel will not play, this is what says why */
  if (opts.log) a.push("--log-file=" + opts.log);
  if (opts.startMs > 0) a.push("--start=" + Math.floor(opts.startMs / 1000));
  if (opts.speed && opts.speed !== 1) a.push("--speed=" + opts.speed);
  /* open exactly over the app, so it reads as the same program going full screen */
  if (opts.box) a.push("--geometry=" + geometry(opts.box));
  return a;
}

/** WxH+X+Y, the shape mpv expects, from the app window's own corners */
function geometry(b) {
  const w = Math.max(320, Math.round(b.width || 0));
  const h = Math.max(240, Math.round(b.height || 0));
  const x = Math.round(b.x || 0);
  const y = Math.round(b.y || 0);
  return w + "x" + h + (x >= 0 ? "+" : "") + x + (y >= 0 ? "+" : "") + y;
}

/* ------------------------------------------------------------------ speaking */

/** mpv answers one JSON object per line over the pipe. */
class Link {
  constructor() { this.sock = null; this.id = 0; this.waiting = new Map(); this.buf = ""; this.onProp = null }

  connect(pipe, tries = 40) {
    const where = pipe || PIPE;
    return new Promise(done => {
      const go = left => {
        const s = net.connect(where);
        s.on("connect", () => {
          this.sock = s;
          s.on("data", d => this.read(d));
          s.on("error", () => { });
          s.on("close", () => { this.sock = null });
          done(true);
        });
        s.on("error", () => {
          s.destroy();
          if (left <= 0) return done(false);
          setTimeout(() => go(left - 1), 150);
        });
      };
      go(tries);
    });
  }

  read(chunk) {
    this.buf += chunk.toString("utf8");
    let at;
    while ((at = this.buf.indexOf("\n")) >= 0) {
      const line = this.buf.slice(0, at).trim();
      this.buf = this.buf.slice(at + 1);
      if (!line) continue;
      let m = null;
      try { m = JSON.parse(line) } catch (e) { continue }
      /* mpv speaks first when something it was asked to watch changes */
      if (m && m.event === "property-change" && this.onProp) {
        try { this.onProp(m.name, m.data) } catch (e) { /* never on mpv's thread */ }
        continue;
      }
      if (m && m.request_id && this.waiting.has(m.request_id)) {
        const done = this.waiting.get(m.request_id);
        this.waiting.delete(m.request_id);
        done(m.error === "success" ? m.data : null);
      }
    }
  }

  send(command) {
    if (!this.sock) return Promise.resolve(null);
    const id = ++this.id;
    return new Promise(done => {
      const timer = setTimeout(() => { this.waiting.delete(id); done(null) }, 3000);
      this.waiting.set(id, v => { clearTimeout(timer); done(v) });
      try { this.sock.write(JSON.stringify({ command, request_id: id }) + "\n") }
      catch (e) { clearTimeout(timer); this.waiting.delete(id); done(null) }
    });
  }

  get(name) { return this.send(["get_property", name]) }
  set(name, v) { return this.send(["set_property", name, v]) }
  close() { try { this.sock && this.sock.destroy() } catch (e) { } this.sock = null }
}

/* -------------------------------------------------------------------- the run */

class Player {
  /**
   * @param exe   where mpv.exe is
   * @param dir   a folder of our own to leave the queue file in
   * @param hooks { onPosition(url, posMs, durMs), onClosed() }
   */
  constructor(exe, dir, hooks, via) {
    this.exe = exe;
    this.dir = dir;
    this.hooks = hooks || {};
    /* where the app's own go-between listens, if it is up */
    this.via = via || null;
    this.child = null;
    this.link = null;
    this.ticker = null;
    this.items = [];
    this.swapping = false;
    this.pipe = null;
  }

  get playing() { return !!this.child }

  async open(items, index, startMs, box) {
    await this.close();
    this.items = items || [];
    if (!this.items.length) return false;
    const at = (index >= 0 && index < this.items.length) ? index : 0;
    const want = this.items[at].u;

    const log = path.join(this.dir, "mpv.log");
    const go = (mode, withBox) => {
      this.pipe = newPipe();
      return this.spawn(mpvArgs({
        items: this.items, index: at, startMs, box: withBox ? box : null, log,
        pipe: this.pipe,
        playlist: writePlaylist(this.items, this.dir, mode, this.via)
      }));
    };

    /* ---- The ways to open it, in turn ----
       1. what suits it: the go-between for channels and lists, mpv itself
          for a film that is one plain file
       2. everything through the go-between, which asks the way the server
          answers (another agent, secure DNS, an old certificate)
       3. FFmpeg's own web code - the last resort, never the first try
       Each one is watched: mpv that moves to another channel, or gives up
       on this one, is the signal for the next way. */
    const modes = this.via ? [viaFirst(want, this.items[at].v) ? "proxy" : "auto", "robust", "all"] : ["auto", "all"];
    const others = this.items.map(x => x && x.u).filter((u, k) => u && k !== at);

    for (let k = 0; k < modes.length; k++) {
      if (k > 0) { this.swapping = true; await this.close() }
      let alive = go(modes[k], true);
      if (alive) { this.listen(); alive = await this.settled() }
      /* a screen arrangement mpv will not have: the same way, without it */
      if (!alive && box) {
        alive = go(modes[k], false);
        if (alive) { this.listen(); alive = await this.settled() }
      }
      this.swapping = false;
      if (!alive || !this.child) continue;

      await this.linkUp();
      const how = await this.watch(want, others);
      if (how === "ok") { this.tick(); this.subsFollow(); return true }
      /* the window was closed by hand while it was still starting: that is
         the person's answer, not a reason to open it again */
      if (how === "dead" && this.lastExit === 0) return false;
    }

    /* it will not open at all: say so, rather than play something else */
    await this.close();
    return this.failed(at);
  }

  /** open the pipe to mpv; started early so settling can end the moment it answers */
  listen() {
    if (this.link) { this.link.close(); this.link = null }
    const l = new Link();
    this.link = l;
    this.linking = l.connect(this.pipe);
    return this.linking;
  }

  async linkUp() {
    try { await this.linking } catch (e) { /* mpv gone; the caller sees that */ }
  }

  /** the page is told which channel would not open, so it can say so */
  failed(at) {
    const it = this.items[at] || {};
    if (this.hooks.onFailed) this.hooks.onFailed(String(it.n || ""));
    return false;
  }

  /**
   * Is mpv playing what it was asked for?
   *
   * "ok"    yes, and the picture has started
   * "wrong" no - it has moved on to something else
   * "dead"  mpv is gone
   *
   * A channel is given all the time it needs; only a channel that is
   * plainly no longer the one asked for cuts the wait short, and it has to
   * read that way twice in a row before it counts.
   */
  async watch(want, others, ms = 25000) {
    /* No pipe to mpv means no way to ask what it is playing. Rather than
       hold everything up guessing, the player behaves exactly as it did
       before any of this was added. */
    if (!this.child) return "dead";
    if (!this.link || !this.link.sock) return "ok";

    /*
     * What counts as having gone astray.
     *
     * Most channels are an address that answers with a list naming where the
     * stream really is, and mpv follows it. What it says it is playing then
     * stops being the address that was asked for - and that is perfectly
     * normal, it is the same channel. Treating any such move as the wrong
     * channel is what took the working channels down with the broken ones.
     *
     * Only one thing is really wrong: mpv playing ANOTHER CHANNEL OF OURS,
     * which is what it does when the one asked for will not open.
     */
    const elsewhere = new Set((others || []).map(bareUrl));
    elsewhere.delete(bareUrl(want));
    const astray = v => v != null && elsewhere.has(bareUrl(v));

    let moved = false;
    if (this.link) {
      /* mpv says so the instant it moves on, which is the difference between
         cutting the wrong channel off unseen and watching it start */
      this.link.onProp = (name, value) => {
        if (name === "path" && astray(value)) moved = true;
      };
      await this.link.send(["observe_property", 1, "path"]);
    }
    const until = Date.now() + ms;
    let asked = 0;
    while (Date.now() < until) {
      await wait(120);
      if (!this.child) return "dead";
      if (moved) return "wrong";
      if (!this.link || !this.link.sock) return this.child ? "ok" : "dead";
      /* a slower check as well, in case the notice never comes */
      if (++asked % 5 === 0) {
        const here = await this.link.get("path");
        if (astray(here)) return "wrong";
        /* running: whatever list it followed to get here, it is playing */
        const t = await this.link.get("time-pos");
        if (typeof t === "number" && t > 0.4) return "ok";
      }
    }
    return "ok";        /* still the right channel, only slow: leave it be */
  }

  spawn(args) {
    /* Windows takes its pipe away with the process; elsewhere the name is a
       file that outlives it, and mpv started on top of a stale one runs deaf.
       Then nothing can be asked of it - including which channel it is on. */
    if (process.platform !== "win32" && this.pipe) {
      try { fs.unlinkSync(this.pipe) } catch (e) { /* not there, which is right */ }
    }
    let c = null;
    try {
      c = spawn(this.exe, args, { stdio: "ignore", windowsHide: false });
    } catch (e) { this.child = null; return false }
    this.child = c;

    /* mpv that ends, and mpv that never started, are the same to everyone else:
       an "error" is what a missing mpv.exe reports, and it never reaches "exit" */
    let over = false;
    this.lastExit = null;
    c.on("exit", code => { if (this.child === c || !this.child) this.lastExit = code });
    const gone = () => {
      if (over) return;
      over = true;
      /* One mpv ending while the next is already up must not take the new
         one's pipe down with it - everything after that would be talking to
         a player that is no longer there. */
      if (this.child !== c) return;
      this.stopTicker();
      this.child = null;
      if (this.link) { this.link.close(); this.link = null }
      /* a second try for the same channel is not the player closing */
      if (!this.swapping && this.hooks.onClosed) this.hooks.onClosed();
    };
    c.on("exit", gone);
    c.on("error", gone);
    return true;
  }

  /**
   * True when mpv is still running a moment after it was asked to start.
   *
   * It answers the moment it knows: mpv that died is gone at once, and mpv
   * that opened its pipe has plainly started. Only mpv that has done
   * neither is waited on for the whole moment - and that wait is what a
   * channel that will not open used to spend playing a different one.
   */
  async settled(ms = 1600) {
    const until = Date.now() + ms;
    while (Date.now() < until) {
      await wait(80);
      if (!this.child) return false;
      if (this.link && this.link.sock) return true;
    }
    return !!this.child;
  }

  /* where the film had got to, so the page can offer to carry on later */
  tick() {
    this.stopTicker();
    this.ticker = setInterval(async () => {
      if (!this.link) return;
      const [pos, dur, url] = await Promise.all([
        this.link.get("time-pos"), this.link.get("duration"), this.link.get("path")
      ]);
      if (this.hooks.onPosition && url && pos > 5 && dur > 0)
        this.hooks.onPosition(bareUrl(url), Math.round(pos * 1000), Math.round(dur * 1000));
    }, 5000);
  }
  stopTicker() { if (this.ticker) { clearInterval(this.ticker); this.ticker = null } }

  /**
   * Subtitle files the page made ready (OpenSubtitles, or Kurdish by Claude)
   * go to mpv with the film or episode they belong to: added when it starts,
   * the chosen one switched on. A queue of episodes gets each its own.
   */
  subsFollow() {
    this.subsDone = null;
    const add = async () => {
      if (!this.link) return;
      const here = await this.link.get("path");
      if (!here || this.subsDone === here) return;
      this.subsDone = here;
      const it = this.items.find(x => x && sameStream(x.u, here));
      if (!it || !Array.isArray(it.subs)) return;
      for (const s of it.subs)
        await this.link.send(["sub-add", String(s.f), s.def ? "select" : "auto", String(s.label || ""), String(s.lang || "")]);
    };
    if (this.link) {
      this.link.onProp = (name, value) => { if (name === "path" && value) add() };
      this.link.send(["observe_property", 2, "path"]);
    }
    add();
  }

  async close() {
    this.stopTicker();
    if (this.link) { await this.link.send(["quit"]); this.link.close(); this.link = null }
    if (this.child) {
      const c = this.child;
      this.child = null;
      try { c.kill() } catch (e) { }
    }
  }
}

module.exports = { Player, mpvArgs, playlistText, writePlaylist, isVod, isHls, viaFirst, geometry,
                   bareUrl, sameStream, PIPE };
