#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
command -v docker >/dev/null
command -v curl >/dev/null
# Preparation is deliberately separate from application deploys. Never replace a live graph in place.
if docker ps --format '{{.Names}}' | grep -qx routepass_osrm; then
  echo 'OSRM is running. Prepare a new data directory and swap it after validation.' >&2
  exit 1
fi
available_kb=$(awk '/MemAvailable/{print $2}' /proc/meminfo)
if [ "$available_kb" -lt 2500000 ]; then echo 'Need at least 2.5 GB free RAM for country graph preparation.' >&2; exit 1; fi
mkdir -p data/osrm
if [ -e data/osrm/ethiopia-latest.osrm.partition ]; then echo 'Prepared graph already exists; no overwrite.'; exit 0; fi
curl --fail --location --retry 3 https://download.geofabrik.de/africa/ethiopia-latest.osm.pbf -o data/osrm/ethiopia-latest.osm.pbf.partial
mv data/osrm/ethiopia-latest.osm.pbf.partial data/osrm/ethiopia-latest.osm.pbf
image=ghcr.io/project-osrm/osrm-backend:v6.0.0
docker run --rm --memory=3g --cpus=2 -v "$PWD/data/osrm:/data" "$image" osrm-extract --threads 2 -p /opt/car.lua /data/ethiopia-latest.osm.pbf
docker run --rm --memory=3g --cpus=2 -v "$PWD/data/osrm:/data" "$image" osrm-partition --threads 2 /data/ethiopia-latest.osrm
docker run --rm --memory=3g --cpus=2 -v "$PWD/data/osrm:/data" "$image" osrm-customize --threads 2 /data/ethiopia-latest.osrm
docker compose -f compose.yml up -d osrm
echo 'OSRM started privately. Verify a known Ethiopian route through the application API.'
