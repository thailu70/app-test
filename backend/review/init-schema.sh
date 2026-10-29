#!/bin/sh
set -eu
# Reuse the current schema, excluding development/public discovery seed rows.
# PostgreSQL runs this only when its separate review volume is empty.
sed '/^-- Initial Seed Data[[:space:]]*$/,$d' /review-schema/base.sql | psql --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB"
