#!/usr/bin/env bash
set -euo pipefail
directory=${1:-infrastructure/secrets}
mkdir -p "$directory"
umask 077
if [[ -e "$directory/fleet-key.pem" ]]; then
  printf 'Private key already exists; refusing to overwrite it.\n' >&2
  exit 1
fi
openssl ecparam -name prime256v1 -genkey -noout -out "$directory/fleet-key.pem"
openssl ec -in "$directory/fleet-key.pem" -pubout -out "$directory/public-key.pem"
openssl pkey -in "$directory/fleet-key.pem" -text -noout | grep -q 'prime256v1'
printf 'Created P-256 key pair in %s\n' "$directory"
