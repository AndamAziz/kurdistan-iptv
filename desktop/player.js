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
function playlistText(items, direct) {
  let out = "#EXTM3U\n";
  for (const it of items) {
    if (!it || !it.u) continue;
    out += "#EXTINF:-1," + String(it.n || "").replace(/[\r\n]+/g, " ") + "\n";
    out += ((direct && isVod(it.u) && /^https?:/i.test(it.u)) ? "lavf://" : "") + it.u + "\n";
  }
  return out;
}

function writePlaylist(items, dir, direct) {
  const file = path.join(dir, direct ? "kiptv-queue.m3u8" : "kiptv-plain.m3u8");
  fs.writeFileSync(file, playlistText(items, direct), "utf8");
  return file;
}

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
    "--input-ipc-server=" + PIPE,
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
    "--stream-lavf-o-append=protocol_whitelist=file,http,https,tcp,tls,crypto,hls,applehttp",
    "--demuxer-lavf-o-append=protocol_whitelist=file,http,https,tcp,tls,crypto,hls,applehttp",

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
  constructor() { this.sock = null; this.id = 0; this.waiting = new Map(); this.buf = "" }

  connect(tries = 40) {
    return new Promise(done => {
      const go = left => {
        const s = net.connect(PIPE);
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
  constructor(exe, dir, hooks) {
    this.exe = exe;
    this.dir = dir;
    this.hooks = hooks || {};
    this.child = null;
    this.link = null;
    this.ticker = null;
    this.items = [];
  }

  get playing() { return !!this.child }

  async open(items, index, startMs, box) {
    await this.close();
    this.items = items || [];
    if (!this.items.length) return false;

    const log = path.join(this.dir, "mpv.log");
    const playlist = writePlaylist(this.items, this.dir, true);
    const args = mpvArgs({ items: this.items, index, startMs, playlist, box, log });

    const started = this.spawn(args);
    if (!started) return false;

    /* Two things can leave mpv with nothing to show: a screen arrangement it
       will not have, and - for a film - the other way of fetching it. Either
       way it is given a second, plainer try rather than left dead. */
    let alive = await this.settled();
    if (!alive) {
      const plain = writePlaylist(this.items, this.dir, false);
      this.spawn(mpvArgs({ items: this.items, index, startMs, playlist: plain, box, log }));
      alive = await this.settled();
      if (!alive && box) {
        this.spawn(mpvArgs({ items: this.items, index, startMs, playlist: plain, log }));
        await this.settled();
      }
    }
    if (!this.child) return false;

    this.link = new Link();
    await this.link.connect();
    this.tick();
    return true;
  }

  spawn(args) {
    let c = null;
    try {
      c = spawn(this.exe, args, { stdio: "ignore", windowsHide: false });
    } catch (e) { this.child = null; return false }
    this.child = c;

    /* mpv that ends, and mpv that never started, are the same to everyone else:
       an "error" is what a missing mpv.exe reports, and it never reaches "exit" */
    let over = false;
    const gone = () => {
      if (over) return;
      over = true;
      this.stopTicker();
      if (this.child === c) this.child = null;
      if (this.link) { this.link.close(); this.link = null }
      if (this.hooks.onClosed) this.hooks.onClosed();
    };
    c.on("exit", gone);
    c.on("error", gone);
    return true;
  }

  /** true when mpv is still running a moment after it was asked to start */
  settled(ms = 1600) {
    return new Promise(done => setTimeout(() => done(!!this.child), ms));
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
        this.hooks.onPosition(url, Math.round(pos * 1000), Math.round(dur * 1000));
    }, 5000);
  }
  stopTicker() { if (this.ticker) { clearInterval(this.ticker); this.ticker = null } }

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

module.exports = { Player, mpvArgs, playlistText, writePlaylist, isVod, geometry, PIPE };
