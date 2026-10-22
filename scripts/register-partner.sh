#!/usr/bin/env bash
set -euo pipefail
: "${TESLA_CLIENT_ID:?Set TESLA_CLIENT_ID}"
: "${TESLA_CLIENT_SECRET:?Set TESLA_CLIENT_SECRET}"
: "${TESLA_PUBLIC_DOMAIN:?Set TESLA_PUBLIC_DOMAIN}"
region=${TESLA_REGION:-CN}
case "$region" in
  CN) auth=https://auth.tesla.cn; fleet=https://fleet-api.prd.cn.vn.cloud.tesla.cn ;;
  NA) auth=https://auth.tesla.com; fleet=https://fleet-api.prd.na.vn.cloud.tesla.com ;;
  EU) auth=https://auth.tesla.com; fleet=https://fleet-api.prd.eu.vn.cloud.tesla.com ;;
  *) printf 'Unknown region\n' >&2; exit 1 ;;
esac
if [[ ! "$TESLA_PUBLIC_DOMAIN" =~ ^[a-zA-Z0-9.-]+$ ]]; then
  printf 'Domain must be a bare hostname\n' >&2; exit 1
fi
curl --fail --silent --show-error --header 'Range: bytes=0-200' "https://$TESLA_PUBLIC_DOMAIN/.well-known/appspecific/com.tesla.3p.public-key.pem" >/dev/null
token=$(curl --fail --silent --show-error "$auth/oauth2/v3/token" \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode "client_id=$TESLA_CLIENT_ID" \
  --data-urlencode "client_secret=$TESLA_CLIENT_SECRET" \
  --data-urlencode "audience=$fleet" \
  --data-urlencode 'scope=openid vehicle_device_data vehicle_cmds vehicle_charging_cmds' | jq -er '.access_token')
curl --fail --silent --show-error "$fleet/api/1/partner_accounts" \
  -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  --data "$(jq -nc --arg domain "$TESLA_PUBLIC_DOMAIN" '{domain:$domain}')" \
  | jq '{registered: (.response != null), domain: .response.domain}'
