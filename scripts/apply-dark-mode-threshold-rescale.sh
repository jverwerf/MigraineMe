#!/bin/bash
# Follow-up to apply-phone-metric-fix.sh: rescales the dark-mode thresholds that
# onboarding derived on the old scale (12.8 and 15.2 hours, 82 users on the
# MigraineMe project), and records the migration on both projects.
#
# No edge functions to redeploy — this is data only. Safe to re-run: the updates
# are bounded to values only the old scale could produce.
#
# Prints the resulting threshold spread so you can see it landed.

set -euo pipefail

PAT="$(tr -d '\n\r ' < ~/keys/supabase-pat.txt)"
MM_REF=qykflarpibofvffmzghi
MS_REF=vpwnhpwiwxwoyjfytiye
VERSION=20260822310000
NAME=dark_mode_threshold_rescale
SQL=~/dev/MigraineMe/supabase/migrations/${VERSION}_${NAME}.sql

post() {  # post <ref> <json-file>
  curl -sS -X POST "https://api.supabase.com/v1/projects/$1/database/query" \
    -H "Authorization: Bearer $PAT" -H "Content-Type: application/json" --data @"$2"
  echo
}

python3 -c "import json,sys;print(json.dumps({'query':open(sys.argv[1]).read()}))" "$SQL" > /tmp/_dmq.json
python3 -c "import json;print(json.dumps({'query':\"insert into supabase_migrations.schema_migrations(version,name) values ('$VERSION','$NAME') on conflict do nothing\"}))" > /tmp/_dmv.json
python3 -c "import json;print(json.dumps({'query':'select label, default_threshold, count(*) users from user_prodromes where label like \'Dark mode%\' or label like \'Brightness%\' group by 1,2 order by 1,2'}))" > /tmp/_dmc.json

echo "── MigraineMe ($MM_REF)"
post "$MM_REF" /tmp/_dmq.json
post "$MM_REF" /tmp/_dmv.json

echo "── MeSeries / VertigoMe ($MS_REF)"
post "$MS_REF" /tmp/_dmq.json
post "$MS_REF" /tmp/_dmv.json

echo "── Thresholds now (expect Dark mode high at 3.65 / 4 / 4.35, nothing above 6)"
post "$MM_REF" /tmp/_dmc.json
