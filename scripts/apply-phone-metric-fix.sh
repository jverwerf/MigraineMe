#!/bin/bash
# Applies the 2026-08-22 phone-behaviour metric fix to both live projects.
#
# WHY THIS SCRIPT EXISTS: the session that wrote the fix could not run the DDL
# itself — the sandbox blocks schema changes through the management API — and
# `supabase db push` refuses on both projects because their migration history
# has drifted from the local files. Everything else (app code, edge functions,
# the migration file) is committed and pushed; this is the one step left.
#
# It is safe to re-run: every statement in the migration is idempotent except
# the brightness rescale, which is guarded on screen_on_samples = 0 and so only
# ever touches rows the fix has not already converted.
#
# Run it, then check the verification output at the bottom.

set -euo pipefail

PAT="$(tr -d '\n\r ' < ~/keys/supabase-pat.txt)"
MM_REF=qykflarpibofvffmzghi          # MigraineMe
MS_REF=vpwnhpwiwxwoyjfytiye          # MeSeries / VertigoMe
MIGRATION_VERSION=20260822300000
MIGRATION_NAME=phone_behavior_screen_on_and_units
MM_SQL=~/dev/MigraineMe/supabase/migrations/${MIGRATION_VERSION}_${MIGRATION_NAME}.sql

run_sql() {   # run_sql <project-ref> <sql-string>
  local ref="$1" sql="$2"
  python3 -c "import json,sys;print(json.dumps({'query':sys.argv[1]}))" "$sql" > /tmp/_sbq.json
  curl -sS -X POST "https://api.supabase.com/v1/projects/$ref/database/query" \
    -H "Authorization: Bearer $PAT" -H "Content-Type: application/json" \
    --data @/tmp/_sbq.json
  echo
}

run_file() {  # run_file <project-ref> <path-to-sql>
  local ref="$1" file="$2"
  python3 -c "import json,sys;print(json.dumps({'query':open(sys.argv[1]).read()}))" "$file" > /tmp/_sbq.json
  curl -sS -X POST "https://api.supabase.com/v1/projects/$ref/database/query" \
    -H "Authorization: Bearer $PAT" -H "Content-Type: application/json" \
    --data @/tmp/_sbq.json
  echo
}

echo "── 1. Migration → MigraineMe ($MM_REF)"
run_file "$MM_REF" "$MM_SQL"
run_sql "$MM_REF" "insert into supabase_migrations.schema_migrations(version,name) values ('$MIGRATION_VERSION','$MIGRATION_NAME') on conflict do nothing"

echo "── 2. Migration → MeSeries / VertigoMe ($MS_REF)"
run_file "$MS_REF" "$MM_SQL"
run_sql "$MS_REF" "insert into supabase_migrations.schema_migrations(version,name) values ('$MIGRATION_VERSION','$MIGRATION_NAME') on conflict do nothing"

echo "── 3. Edge functions → MigraineMe (all six are verify_jwt=true, no flag needed)"
cd ~/dev/migraineme-ios
~/bin/supabase functions deploy prodrome-worker trigger-worker \
  hourly-prodrome-dispatcher hourly-trigger-dispatcher \
  daily-9am-phone-behavior build-report-html --project-ref "$MM_REF"

echo "── 4. Edge functions → MeSeries"
cd ~/dev/MeSeries
# These three are verify_jwt=false on the MeSeries project and their crons send
# only x-cron-secret. Deploying without the flag flips them to JWT-required and
# breaks the crons — that is the 2026-08-19 incident, do not drop it.
~/bin/supabase functions deploy hourly-prodrome-dispatcher hourly-trigger-dispatcher \
  daily-9am-phone-behavior --project-ref "$MS_REF" --no-verify-jwt
~/bin/supabase functions deploy prodrome-worker trigger-worker build-report-html \
  --project-ref "$MS_REF"

echo "── 5. Verification"
echo "Brightness history should now read 0-100, and the two aggregates should"
echo "return a screen_on_samples column:"
run_sql "$MM_REF" "select date, round(value_mean::numeric,1) as mean_pct, value_max, sample_count, screen_on_samples from phone_brightness_daily order by date desc limit 5"
run_sql "$MM_REF" "select label, default_threshold, unit from user_prodromes where label in ('Brightness low','Brightness high','Dark mode high','Dark mode low') group by label, default_threshold, unit order by label"
