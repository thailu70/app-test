#!/usr/bin/env bash
# Refresh only the OSRM road graph. Never modify files in the mounted live graph.
set -Eeuo pipefail
umask 022
root=$(cd "$(dirname "$(readlink -f "$0")")" && pwd -P)
data="$root/data"
live="$data/osrm"
updates="$root/updates"
container=routepass_osrm
source_url=https://download.geofabrik.de/africa/ethiopia-latest.osm.pbf
action=${1:-prepare}
for tool in docker curl python3 sha256sum md5sum realpath flock; do command -v "$tool" >/dev/null; done
[ -d "$data" ] && [ ! -L "$data" ] && [ ! -L "$live" ] || { echo 'Unsafe/missing data directory.' >&2; exit 1; }
[ "$(realpath -e "$data")" = "$data" ] || { echo 'Data path must not contain symlinks.' >&2; exit 1; }
mkdir -p "$updates"
[ ! -L "$updates" ] && [ "$(realpath -e "$updates")" = "$updates" ] || { echo 'Unsafe update metadata directory.' >&2; exit 1; }
exec 9>"$updates/update.lock"
flock -n 9 || { echo 'Another graph update is running.' >&2; exit 1; }

checked_id() {
  [[ "$1" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || { echo 'Expected the update timestamp ID.' >&2; exit 1; }
}
inspect_live() {
  image=$(docker inspect "$container" --format '{{.Image}}')
  probe_image=$(docker inspect routepass_api --format '{{.Image}}')
  docker inspect "$container" >"$run/live-container-before.json"
  python3 - "$run/live-container-before.json" "$live" "$action" <<'PY'
import json,sys
c=json.load(open(sys.argv[1]))[0]
assert c['State']['Running'] or sys.argv[3]=='rollback', 'Live OSRM must be running before preparing/swapping.'
assert c['Config']['Cmd']==['osrm-routed','--algorithm','mld','--threads','2','/data/ethiopia-latest.osrm'], 'Unexpected routing command'
assert c['HostConfig']['ReadonlyRootfs'] and not c['HostConfig']['PortBindings'], 'Unexpected OSRM security/ports'
assert len(c['Mounts'])==1 and c['Mounts'][0]['Source']==sys.argv[2] and c['Mounts'][0]['Destination']=='/data' and not c['Mounts'][0]['RW'], 'Unexpected live mount'
assert c['Config']['Labels']['com.docker.compose.project.working_dir']==sys.argv[2].rsplit('/data/osrm',1)[0], 'Unexpected Compose root'
PY
}
probe_graph() {
  local network=$1 host=$2 output=$3
  docker run --rm -i --network "$network" --memory=128m --memory-swap=128m --cpus=0.25 \
    --read-only --cap-drop ALL --security-opt no-new-privileges:true \
    --entrypoint node "$probe_image" - "$host" >"$output" <<'JS'
const cases = [
 ['addis-bole',[38.7615,9.0102],[38.7991,8.9821],2000,25000],
 ['addis-entoto',[38.7615,9.0102],[38.7500,9.0910],4000,45000],
 ['addis-hawassa',[38.7615,9.0102],[38.4760,7.0480],150000,500000],
 ['addis-bahir-dar',[38.7615,9.0102],[37.3900,11.5930],300000,900000],
 ['addis-mekelle',[38.7615,9.0102],[39.4750,13.4970],400000,1200000]
];
(async()=>{
 const results=[];
 for(const [name,a,b,min,max] of cases){
  const url=`http://${process.argv[2]}:5000/route/v1/driving/${a.join(',')};${b.join(',')}?overview=full&geometries=geojson&steps=false&radiuses=500;500`;
  let value;
  for(let i=0;i<30;i++){
   try{const r=await fetch(url,{signal:AbortSignal.timeout(15000)});if(!r.ok)throw Error(`HTTP ${r.status}`);value=await r.json();break;}
   catch(e){if(i===29)throw e;await new Promise(r=>setTimeout(r,1000));}
  }
  const route=value.routes?.[0], points=route?.geometry?.coordinates;
  if(value.code!=='Ok'||!Number.isFinite(route?.distance)||route.distance<min||route.distance>max||!(route.duration>0)||!Array.isArray(points)||points.length<3||points.some(p=>!p.every(Number.isFinite))||value.waypoints.some(p=>p.distance>500))throw Error(`${name}: invalid road route`);
  results.push({name,distanceMeters:route.distance,durationSeconds:route.duration,geometryPoints:points.length,dataVersion:value.data_version||null});
 }
 console.log(JSON.stringify({checkedAt:new Date().toISOString(),passed:true,routes:results},null,2));
})().catch(e=>{console.error(e);process.exitCode=1;});
JS
}
cleanup_candidate() {
  if [ -n "${candidate:-}" ] && docker container inspect "$candidate" >/dev/null 2>&1; then
    [ "$(docker inspect "$candidate" --format '{{index .Config.Labels "passo.osrm.update"}}')" = "$id" ] || return 1
    docker stop --time 10 "$candidate" >/dev/null
    docker rm "$candidate" >/dev/null
  fi
  if [ -n "${network:-}" ] && docker network inspect "$network" >/dev/null 2>&1; then
    [ "$(docker network inspect "$network" --format '{{index .Labels "passo.osrm.update"}}')" = "$id" ] || return 1
    docker network rm "$network" >/dev/null
  fi
}
compose_osrm() {
  # The override pins the already-running platform image ID. No pulls/builds or dependencies.
  docker compose --project-directory "$root" -p routepass-geo -f "$root/compose.yml" \
    -f "$run/pinned-image.yml" up -d --no-deps --force-recreate --pull never osrm
}
recover_swap() {
  local status=$1
  trap - ERR INT TERM
  set +e
  echo "Routing swap interrupted/failed ($status); restoring the pre-operation graph." >&2
  docker stop --time 20 "$container" >/dev/null 2>&1
  if [ -d "$previous" ] && [ ! -L "$previous" ]; then
    if [ -d "$live" ] && [ ! -e "$incoming" ]; then mv -T "$live" "$incoming"; fi
    if [ ! -e "$live" ]; then mv -T "$previous" "$live"; fi
  fi
  if [ -d "$live" ] && [ ! -L "$live" ] && compose_osrm && probe_graph routepass_internal_net osrm "$run/recovery-routes.json"; then
    echo "Pre-operation graph restored. All graph directories and metadata were retained." >&2
    python3 - "$run/manifest.json" <<'PY'
import datetime,json,sys
p=sys.argv[1];m=json.load(open(p));m.update(status='RECOVERED_AFTER_FAILURE',recoveredAt=datetime.datetime.now(datetime.timezone.utc).isoformat());open(p,'w').write(json.dumps(m,indent=2)+'\n')
PY
  else
    echo "RECOVERY NEEDS ATTENTION: live=$live previous=$previous incoming=$incoming; no graph files deleted." >&2
  fi
  exit "$status"
}

case "$action" in
prepare)
  id=$(date -u +%Y%m%dT%H%M%SZ)
  run="$updates/$id"
  stage="$data/osrm-staging-$id"
  [ ! -e "$run" ] && [ ! -e "$stage" ] || { echo 'Update path already exists.' >&2; exit 1; }
  [ "$(awk '/MemAvailable/{print $2}' /proc/meminfo)" -ge 4000000 ] || { echo 'Need 4 GB available RAM before the capped 3 GB preparation.' >&2; exit 1; }
  [ "$(df -Pk "$data" | awk 'NR==2{print $4}')" -ge 5000000 ] || { echo 'Need 5 GB free disk.' >&2; exit 1; }
  mkdir "$run" "$stage"
  inspect_live
  printf '%s\n' "$image" >"$run/image-id.txt"
  printf '%s\n' "$probe_image" >"$run/probe-image-id.txt"
  dated_url=$(curl --fail --silent --show-error --head --location --retry 3 --max-time 90 \
    --proto '=https' --proto-redir '=https' -D "$run/source-headers.txt" -o /dev/null -w '%{url_effective}' "$source_url")
  [[ "$dated_url" =~ ^https://download\.geofabrik\.de/africa/ethiopia-[0-9]{6}\.osm\.pbf$ ]] || { echo 'Expected an official dated Ethiopia extract URL.' >&2; exit 1; }
  curl --fail --silent --show-error --location --retry 3 --max-time 90 --proto '=https' --proto-redir '=https' "$dated_url.md5" -o "$run/source.md5"
  expected=$(awk 'NR==1{print $1}' "$run/source.md5")
  [[ "$expected" =~ ^[a-fA-F0-9]{32}$ ]] || { echo 'Invalid upstream checksum.' >&2; exit 1; }
  echo "Downloading $dated_url"
  curl --fail --location --retry 3 --connect-timeout 15 --max-time 900 --proto '=https' --proto-redir '=https' "$dated_url" -o "$stage/ethiopia-latest.osm.pbf.partial"
  printf '%s  %s\n' "$expected" "$stage/ethiopia-latest.osm.pbf.partial" | md5sum --check --status
  mv -T "$stage/ethiopia-latest.osm.pbf.partial" "$stage/ethiopia-latest.osm.pbf"
  sha256sum "$stage/ethiopia-latest.osm.pbf" >"$run/source.sha256"
  for operation in extract partition customize; do
    args=("osrm-$operation" --threads 1)
    if [ "$operation" = extract ]; then args+=(-p /opt/car.lua /data/ethiopia-latest.osm.pbf); else args+=(/data/ethiopia-latest.osrm); fi
    echo "Preparing $operation with the existing image; maximum 3 GB RAM and 1 CPU."
    docker run --rm --network none --memory=3g --memory-swap=3g --cpus=1 --pids-limit=128 \
      --read-only --tmpfs /tmp:rw,nosuid,size=64m --cap-drop ALL --security-opt no-new-privileges:true \
      --label "passo.osrm.update=$id" -v "$stage:/data" "$image" "${args[@]}" >"$run/$operation.log" 2>&1
  done
  network="routepass-osrm-update-$id"
  candidate="routepass-osrm-candidate-$id"
  trap cleanup_candidate EXIT
  docker network create --internal --label "passo.osrm.update=$id" "$network" >/dev/null
  docker run -d --name "$candidate" --label "passo.osrm.update=$id" --network "$network" \
    --memory=2g --memory-swap=2g --cpus=1.5 --read-only --cap-drop ALL --security-opt no-new-privileges:true \
    -v "$stage:/data:ro" "$image" osrm-routed --algorithm mld --threads 2 /data/ethiopia-latest.osrm >/dev/null
  probe_graph "$network" "$candidate" "$run/candidate-routes.json"
  docker logs "$candidate" >"$run/candidate.log" 2>&1
  cleanup_candidate
  candidate= network=
  (cd "$stage" && find . -maxdepth 1 -type f -name 'ethiopia-latest.osrm*' -print0 | sort -z | xargs -0 sha256sum) >"$run/graph.sha256"
  python3 - "$run" "$stage" "$dated_url" "$expected" "$image" <<'PY'
import datetime,json,pathlib,sys
run,stage,url,md5,image=sys.argv[1:]
r=pathlib.Path(run)
manifest={'id':r.name,'status':'VALIDATED','preparedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'stagePath':stage,'sourceUrl':url,'sourceMd5':md5,'sourceSha256':(r/'source.sha256').read_text().split()[0],'imageId':image,'algorithm':'mld','profile':'/opt/car.lua','preparationMemoryBytes':3221225472,'preparationCpus':1,'candidateChecks':json.loads((r/'candidate-routes.json').read_text()),'graphChecksums':'graph.sha256','sourceHeaders':'source-headers.txt','osmTimestampLog':'extract.log'}
(r/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
PY
  echo "VALIDATED: $run/manifest.json"
  echo "Activate separately: bash $0 activate $id"
  ;;
activate|rollback)
  id=${2:?Pass the prepared update ID.}
  checked_id "$id"
  run="$updates/$id"
  stage="$data/osrm-staging-$id"
  backup="$data/osrm-backup-$id"
  failed="$data/osrm-replaced-$id"
  [ -f "$run/manifest.json" ] && [ ! -L "$run" ] || { echo 'Missing/unsafe manifest.' >&2; exit 1; }
  image=$(cat "$run/image-id.txt")
  probe_image=$(cat "$run/probe-image-id.txt")
  [[ "$image" =~ ^sha256:[a-f0-9]{64}$ ]] || exit 1
  [ "$(docker inspect "$container" --format '{{.Image}}')" = "$image" ] || { echo 'Live engine image changed; prepare again.' >&2; exit 1; }
  printf 'services:\n  osrm:\n    image: "%s"\n' "$image" >"$run/pinned-image.yml"
  # Recheck live mount/security before touching either directory.
  inspect_live
  if [ "$action" = activate ]; then
    [ -d "$stage" ] && [ ! -L "$stage" ] && [ "$(realpath -e "$stage")" = "$stage" ] && [ ! -e "$backup" ] && [ ! -e "$failed" ] || { echo 'Unsafe activation paths.' >&2; exit 1; }
    python3 - "$run/manifest.json" "$stage" "$image" <<'PY'
import json,sys
m=json.load(open(sys.argv[1]));assert m['status']=='VALIDATED' and m['stagePath']==sys.argv[2] and m['imageId']==sys.argv[3] and m['candidateChecks']['passed']
PY
    (cd "$stage" && sha256sum --check "$run/graph.sha256" >/dev/null)
    [ "$(stat -c %d "$stage")" = "$(stat -c %d "$live")" ] || { echo 'Graph swap must stay on the same filesystem.' >&2; exit 1; }
    incoming=$stage
    previous=$backup
  else
    [ -d "$backup" ] && [ ! -L "$backup" ] && [ "$(realpath -e "$backup")" = "$backup" ] && [ ! -e "$failed" ] || { echo 'Unsafe/missing rollback paths.' >&2; exit 1; }
    incoming=$backup
    previous=$failed
  fi
  # Arm recovery BEFORE stopping OSRM: errors/signals at every rename/start phase
  # restore the pre-operation directory, including failure of the first rename.
  # A machine power loss/SIGKILL still requires explicit operator recovery.
  trap 'recover_swap $?' ERR
  trap 'recover_swap 130' INT
  trap 'recover_swap 143' TERM
  docker stop --time 20 "$container" >/dev/null
  mv -T "$live" "$previous"
  mv -T "$incoming" "$live"
  compose_osrm
  probe_graph routepass_internal_net osrm "$run/$action-routes.json"
  docker inspect "$container" >"$run/live-container-after.json"
  python3 - "$run/manifest.json" "$action" "$backup" <<'PY'
import datetime,json,sys
p=sys.argv[1];m=json.load(open(p));at=datetime.datetime.now(datetime.timezone.utc).isoformat()
if sys.argv[2]=='activate':m.update(status='ACTIVE',activatedAt=at,backupPath=sys.argv[3])
else:m.update(status='ROLLED_BACK',rolledBackAt=at)
open(p,'w').write(json.dumps(m,indent=2)+'\n')
PY
  trap - ERR INT TERM
  echo "$action SUCCEEDED: $run/manifest.json; pre-operation graph preserved at $previous"
  ;;
*) echo "Usage: bash $0 prepare | activate ID | rollback ID" >&2; exit 2 ;;
esac
