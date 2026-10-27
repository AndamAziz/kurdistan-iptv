-- one row: the last answer kiptv-football got from the football API
create table if not exists public.kiptv_football_cache (
  id         int primary key check (id = 1),
  body       jsonb not null,
  fetched_at timestamptz not null default now()
);
alter table public.kiptv_football_cache enable row level security;
-- no policies: only the edge function (service_role) reads or writes it
revoke all on public.kiptv_football_cache from anon, authenticated;
