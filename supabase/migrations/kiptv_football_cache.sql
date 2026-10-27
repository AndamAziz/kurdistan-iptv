-- one row: the last answer kiptv-football got from the football API
create table if not exists public.kiptv_football_cache (
  id         int primary key check (id = 1),
  body       jsonb not null,
  fetched_at timestamptz not null default now()
);
alter table public.kiptv_football_cache enable row level security;
-- no policies: only the edge function (service_role) reads or writes it
revoke all on public.kiptv_football_cache from anon, authenticated;
-- new tables get no default grants here: the edge function needs these
grant select, insert, update, delete on public.kiptv_football_cache to service_role;
