#!/bin/sh
# Runs once, when the Docker volume is first initialised. Creates the least-privilege application role.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
     -v app_password="$CLUB_DB_PASSWORD" -v db="$POSTGRES_DB" <<'SQL'
CREATE ROLE club_app LOGIN PASSWORD :'app_password';
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO club_app;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA club AUTHORIZATION club_app;
ALTER ROLE club_app IN DATABASE :"db" SET search_path = club;
SQL
