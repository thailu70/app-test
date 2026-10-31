#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
for command in docker curl md5sum sha256sum; do command -v "$command" >/dev/null; done
if docker ps --format '{{.Names}}' | grep -qx routepass_photon; then
  echo 'Photon is already running. Prepare updates in a separate directory.' >&2; exit 1
fi
if [ -e data/photon ]; then echo 'Photon target already exists; refusing to replace it.' >&2; exit 1; fi
available_kb=$(awk '/MemAvailable/{print $2}' /proc/meminfo)
if [ "$available_kb" -lt 2500000 ]; then echo 'Need at least 2.5 GB available RAM for import.' >&2; exit 1; fi
mkdir -p data
disk_kb=$(df -Pk data | awk 'NR==2{print $4}')
if [ "$disk_kb" -lt 8000000 ]; then echo 'Need at least 8 GB free disk for regional data and staging.' >&2; exit 1; fi
# Official pinned 1.x release; digest verified against GitHub's release asset listing.
# https://github.com/komoot/photon/releases/expanded_assets/1.3.0
printf '%s  photon.jar.partial\n' a89707c0045e4807b2a1180e132e68e108d998709f48b6c94b98a6e281f571a5 > data/photon-expected.sha256
curl --fail --location --retry 3 https://github.com/komoot/photon/releases/download/1.3.0/photon-1.3.0.jar -o data/photon.jar.partial
(cd data && sha256sum --check photon-expected.sha256)
mv data/photon.jar.partial data/photon.jar
# Regional source contains Horn of Africa; import explicitly filters to Ethiopia only.
dump=photon-dump-horn-of-africa-1.0-latest.jsonl.zst
base=https://download1.graphhopper.com/public/africa/horn-of-africa
if ! curl --fail --location --retry 3 "$base/$dump" -o "data/photon-regional.jsonl.zst.partial"; then
  # The official regional directory may be temporarily absent while dumps are rotated.
  # A verified Africa dump is still bounded and is filtered to ET during import. Never use planet data.
  dump=photon-dump-africa-1.0-latest.jsonl.zst
  base=https://download1.graphhopper.com/public/africa
  curl --fail --location --retry 3 "$base/$dump" -o "data/photon-regional.jsonl.zst.partial"
fi
curl --fail --location --retry 3 "$base/$dump.md5" -o data/photon-dump.md5
expected=$(awk 'NR==1{print $1}' data/photon-dump.md5)
if ! [[ "$expected" =~ ^[a-fA-F0-9]{32}$ ]]; then echo 'Invalid upstream checksum.' >&2; exit 1; fi
printf '%s  photon-regional.jsonl.zst.partial\n' "$expected" > data/photon-check.md5
(cd data && md5sum --check photon-check.md5)
mv data/photon-regional.jsonl.zst.partial data/photon-regional.jsonl.zst
if command -v zstd >/dev/null; then
  zstd --stdout -d data/photon-regional.jsonl.zst > data/photon-regional.jsonl
else
  docker run --rm --memory=256m --cpus=1 -v "$PWD/data:/data" alpine:3.20 sh -ec 'apk add --no-cache zstd >/dev/null; zstd --stdout -d /data/photon-regional.jsonl.zst > /data/photon-regional.jsonl'
fi
stage="data/photon-stage-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir "$stage"
chown 1000:1000 "$stage"
docker run --rm --memory=2g --cpus=1.5 --user 1000:1000 -v "$PWD/$stage:/data" -v "$PWD/data/photon.jar:/opt/photon.jar:ro" -v "$PWD/data/photon-regional.jsonl:/input/regional.jsonl:ro" -w /data eclipse-temurin:21-jre java -Xms256m -Xmx1g -jar /opt/photon.jar import -import-file /input/regional.jsonl -country-codes ET -languages en,am,om -data-dir /data 2>&1 | tee data/photon-import.log
# Photon can exit zero after an import IO failure. Require its explicit success marker.
grep -q 'Database has been successfully set up' data/photon-import.log
mv "$stage" data/photon
docker compose -f compose.yml up -d photon
echo 'Photon started privately. Check /api/mobility/locations?q=Addis before declaring search ready.'
