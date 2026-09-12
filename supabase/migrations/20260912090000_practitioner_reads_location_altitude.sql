-- Altitude is charted on the app's Environment page and lives here, not in
-- user_weather_daily. Same 'weather' scope as the rest of that page.
do $$
begin
  if exists (select 1 from information_schema.columns
    where table_schema='public' and table_name='user_location_daily' and column_name='user_id') then
    drop policy if exists "consented practitioner reads" on public.user_location_daily;
    create policy "consented practitioner reads" on public.user_location_daily
      for select using (public.practitioner_can_read(user_id, 'weather'));
  end if;
end $$;
