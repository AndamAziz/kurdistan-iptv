// kiptv-football: the scores for the ticker on the Live TV home screen.
//
// The app never sees the football API key. This function holds it as a
// secret, asks the provider at most once per FOOTBALL_TTL seconds (the answer
// is kept in kiptv_football_cache, shared by every device), and hands the
// app one small list it can draw as it is.
//
// Secrets:
//   FOOTBALL_API_KEY   the provider's key (required)
//   FOOTBALL_PROVIDER  "apisports" (api-football.com / api-sports.io)
//                      or "footballdata" (football-data.org)   default apisports
//   FOOTBALL_LEAGUES   comma list of league ids (apisports) or competition
//                      codes (footballdata); empty = the provider default below
//   FOOTBALL_TTL       seconds between real requests; default 900 for
//                      apisports (100 requests a day on the free plan), 60 for
//                      footballdata
//
// Answer: { ok, updated, matches:[{ s, min, home, away, hs, as, t, lg }] }
//   s   "live" | "ht" | "ns" | "ft"      t  kick-off, ISO 8601 UTC
// Nothing is invented: no key, or the provider failing with nothing cached,
// gives { ok:false, matches:[] } and the app shows only its welcome line.

import { createClient } from "jsr:@supabase/supabase-js@2";

const CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "*",
  "Content-Type": "application/json; charset=utf-8",
};

type Match = {
  s: "live" | "ht" | "ns" | "ft";
  min: number | null;
  home: string;
  away: string;
  hs: number | null;
  as: number | null;
  t: string;
  lg: string;
};

const PROVIDER = (Deno.env.get("FOOTBALL_PROVIDER") || "apisports").toLowerCase();
const KEY = Deno.env.get("FOOTBALL_API_KEY") || "";
const TTL = Number(Deno.env.get("FOOTBALL_TTL")) ||
  (PROVIDER === "footballdata" ? 60 : 900);

// the big European leagues and cups, the Saudi Pro League, World Cup, Euro
const AS_LEAGUES = [39, 140, 135, 78, 61, 2, 3, 848, 307, 1, 4];
// every competition football-data.org gives on its free plan
const FD_LEAGUES = ["PL", "PD", "SA", "BL1", "FL1", "CL", "EC", "WC", "DED", "PPL", "ELC", "BSA"];

function leagues(): string[] {
  const own = (Deno.env.get("FOOTBALL_LEAGUES") || "").split(",").map((x) => x.trim()).filter(Boolean);
  if (own.length) return own;
  return PROVIDER === "footballdata" ? FD_LEAGUES : AS_LEAGUES.map(String);
}

function day(offset: number): string {
  return new Date(Date.now() + offset * 86400000).toISOString().slice(0, 10);
}

// ---- api-football (api-sports.io) --------------------------------------
async function fromApiSports(): Promise<Match[]> {
  const want = new Set(leagues());
  const r = await fetch("https://v3.football.api-sports.io/fixtures?date=" + day(0), {
    headers: { "x-apisports-key": KEY },
  });
  const j = await r.json();
  const errs = j && j.errors;
  if (!r.ok || (errs && (Array.isArray(errs) ? errs.length : Object.keys(errs).length))) {
    throw new Error("apisports: " + r.status + " " + JSON.stringify(errs || "").slice(0, 200));
  }
  const out: Match[] = [];
  for (const x of j.response || []) {
    if (want.size && !want.has(String(x.league?.id))) continue;
    const st = String(x.fixture?.status?.short || "");
    const s = st === "HT" ? "ht"
      : ["1H", "2H", "ET", "BT", "P", "LIVE", "INT"].includes(st) ? "live"
      : ["FT", "AET", "PEN"].includes(st) ? "ft"
      : st === "NS" ? "ns" : null;
    if (!s) continue; // postponed, cancelled, abandoned, TBD: not shown
    out.push({
      s,
      min: s === "live" ? (x.fixture?.status?.elapsed ?? null) : null,
      home: String(x.teams?.home?.name || ""),
      away: String(x.teams?.away?.name || ""),
      hs: x.goals?.home ?? null,
      as: x.goals?.away ?? null,
      t: new Date(x.fixture?.date).toISOString(),
      lg: String(x.league?.name || ""),
    });
  }
  return out;
}

// ---- football-data.org -------------------------------------------------
async function fromFootballData(): Promise<Match[]> {
  const u = "https://api.football-data.org/v4/matches?dateFrom=" + day(-1) + "&dateTo=" + day(1) +
    "&competitions=" + leagues().join(",");
  const r = await fetch(u, { headers: { "X-Auth-Token": KEY } });
  const j = await r.json();
  if (!r.ok) throw new Error("footballdata: " + r.status + " " + String(j?.message || "").slice(0, 200));
  const out: Match[] = [];
  for (const x of j.matches || []) {
    const st = String(x.status || "");
    const s = st === "PAUSED" ? "ht"
      : ["IN_PLAY", "LIVE"].includes(st) ? "live"
      : st === "FINISHED" ? "ft"
      : ["SCHEDULED", "TIMED"].includes(st) ? "ns" : null;
    if (!s) continue;
    out.push({
      s,
      min: s === "live" && typeof x.minute === "number" ? x.minute : null,
      home: String(x.homeTeam?.shortName || x.homeTeam?.name || ""),
      away: String(x.awayTeam?.shortName || x.awayTeam?.name || ""),
      hs: x.score?.fullTime?.home ?? null,
      as: x.score?.fullTime?.away ?? null,
      t: new Date(x.utcDate).toISOString(),
      lg: String(x.competition?.name || ""),
    });
  }
  return out;
}

// live first, then what starts soonest, then what ended last
function order(list: Match[]): Match[] {
  const now = Date.now();
  const rank = (m: Match) => (m.s === "live" || m.s === "ht") ? 0 : m.s === "ns" ? 1 : 2;
  return list
    .filter((m) => m.home && m.away)
    // finals older than a day and kick-offs more than a day away are not news
    .filter((m) => Math.abs(Date.parse(m.t) - now) < 30 * 3600000)
    .sort((a, b) => {
      const d = rank(a) - rank(b);
      if (d) return d;
      return rank(a) === 2 ? Date.parse(b.t) - Date.parse(a.t) : Date.parse(a.t) - Date.parse(b.t);
    })
    .slice(0, 30);
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  const reply = (o: unknown) => new Response(JSON.stringify(o), { headers: CORS });

  const db = createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!);
  const { data: row } = await db.from("kiptv_football_cache").select("body, fetched_at").eq("id", 1).maybeSingle();
  const age = row ? (Date.now() - Date.parse(row.fetched_at)) / 1000 : Infinity;
  if (row && age < TTL) return reply(row.body);

  if (!KEY) return reply(row?.body || { ok: false, reason: "nokey", matches: [] });

  // claim the refresh first, so a crowd of devices arriving together makes
  // one request to the provider and not a hundred
  const stamp = new Date().toISOString();
  if (row) {
    const { data: won } = await db.from("kiptv_football_cache")
      .update({ fetched_at: stamp }).eq("id", 1).eq("fetched_at", row.fetched_at).select("id");
    if (!won || !won.length) return reply(row.body);
  }

  try {
    const matches = order(PROVIDER === "footballdata" ? await fromFootballData() : await fromApiSports());
    const body = { ok: true, updated: stamp, matches };
    await db.from("kiptv_football_cache").upsert({ id: 1, body, fetched_at: stamp });
    return reply(body);
  } catch (e) {
    console.error(String(e));
    // the last good answer is still true enough to show; nothing is made up
    return reply(row?.body || { ok: false, reason: "provider", matches: [] });
  }
});
