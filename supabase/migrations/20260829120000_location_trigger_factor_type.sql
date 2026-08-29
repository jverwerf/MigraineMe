-- Trigger → onset location rows written by compute-correlation-stats
-- (severity-predictor section): factor_type 'location_trigger', factor_name =
-- the trigger, factor_b = the head-map (or ear-map) location id.

alter table public.correlation_stats
  drop constraint correlation_stats_factor_type_check;

alter table public.correlation_stats
  add constraint correlation_stats_factor_type_check
  check (factor_type = any (array[
    'trigger'::text,
    'metric'::text,
    'interaction'::text,
    'treatment'::text,
    'treatment_interaction'::text,
    'well_done'::text,
    'well_done_chain'::text,
    'location_trigger'::text
  ]));
