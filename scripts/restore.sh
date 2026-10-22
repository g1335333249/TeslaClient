#!/usr/bin/env bash
set -euo pipefail
input=${1:?Usage: scripts/restore.sh backup.sql}
docker compose exec -T mysql sh -c 'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' < "$input"
