#!/usr/bin/env bash
set -euo pipefail
project_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
environment_path="$project_directory/.env"
[[ -f "$environment_path" ]] || { printf '%s\n' 'Missing .env. Run bash scripts/init-env.sh first.' >&2; exit 1; }

database_name=''
database_user=''
database_password=''
database_port=''
app_port=''
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line%$'\r'}"
  [[ -z "$line" || "$line" == \#* ]] && continue
  [[ "$line" =~ ^[A-Z_][A-Z0-9_]*= ]] || { printf '%s\n' 'Invalid .env line; expected NAME=value.' >&2; exit 1; }
  name="${line%%=*}"
  value="${line#*=}"
  case "$name" in
    POSTGRES_DB) database_name="$value" ;;
    POSTGRES_USER) database_user="$value" ;;
    POSTGRES_PASSWORD) database_password="$value" ;;
    PG_PORT) database_port="$value" ;;
    APP_PORT) app_port="$value" ;;
    JWT_SECRET|ADMIN_USERNAME|ADMIN_PASSWORD|JWT_TTL_SECONDS) export "$name=$value" ;;
  esac
done < "$environment_path"

[[ -n "$database_name" && -n "$database_user" && -n "$database_password" && -n "$database_port" && -n "$app_port" && -n "${JWT_SECRET:-}" && -n "${ADMIN_USERNAME:-}" && -n "${ADMIN_PASSWORD:-}" ]] || {
  printf '%s\n' 'Missing required settings in .env.' >&2
  exit 1
}
export DATABASE_URL="jdbc:postgresql://localhost:$database_port/$database_name"
export DATABASE_USER="$database_user"
export DATABASE_PASSWORD="$database_password"
export PORT="$app_port"
cd "$project_directory"
exec bash ./mvnw compile exec:java
