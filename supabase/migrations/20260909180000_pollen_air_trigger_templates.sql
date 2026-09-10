-- Pollen + air quality trigger templates.
-- Apply to BOTH projects (MigraineMe and MeSeries/VertigoMe).
-- Hold until the pollen UI ships: these rows appear in the trigger picker
-- as soon as they exist.
insert into trigger_templates
  (id, label, category, icon_key, prediction_value, direction, default_threshold,
   unit, enabled_by_default, metric_table, metric_column, baseline_days,
   metric_type, display_group, suggested, suggested_condition)
values
  ('6be3a1e5-3f56-437f-812c-d90c73dfaecc', 'Pollen high', 'Environment', 'allergies', 'NONE', 'high', 3, '', true, 'user_weather_daily', 'pollen_overall_index', 14, 'nightly', null, false, null),
  ('ab54e596-c96e-4a91-b7b4-0921a1917c81', 'Tree pollen high', 'Environment', 'allergies', 'NONE', 'high', 3, '', false, 'user_weather_daily', 'pollen_tree_index', 14, 'nightly', null, false, null),
  ('3999adac-099f-4ad2-a8a2-2df8e305d767', 'Grass pollen high', 'Environment', 'allergies', 'NONE', 'high', 3, '', false, 'user_weather_daily', 'pollen_grass_index', 14, 'nightly', null, false, null),
  ('8dd34e50-8678-4a50-b776-fccec602263c', 'Weed pollen high', 'Environment', 'allergies', 'NONE', 'high', 3, '', false, 'user_weather_daily', 'pollen_weed_index', 14, 'nightly', null, false, null),
  ('cb91fd37-ef8d-4158-a19e-7670f45ff5c0', 'Air quality poor', 'Environment', 'smoke', 'NONE', 'high', 25, 'ug/m3', true, 'user_weather_daily', 'pm2_5_mean', 14, 'nightly', null, false, null),
  ('5f207bc1-2e75-4425-a452-649ac7b1031f', 'Coarse dust high', 'Environment', 'smoke', 'NONE', 'high', 50, 'ug/m3', false, 'user_weather_daily', 'pm10_mean', 14, 'nightly', null, false, null),
  ('81c32774-52e1-4c42-9ce2-de71329b0758', 'Ozone high', 'Environment', 'smoke', 'NONE', 'high', 120, 'ug/m3', false, 'user_weather_daily', 'ozone_max', 14, 'nightly', null, false, null)
on conflict (id) do nothing;
