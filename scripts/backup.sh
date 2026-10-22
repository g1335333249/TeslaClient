#!/usr/bin/env bash
set -euo pipefail
output=${1:-voltmate-backup.sql}
docker compose exec -T mysql sh -c 'exec mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers "$MYSQL_DATABASE"' > "$output"
printf 'Backup written to %s\n' "$output"
