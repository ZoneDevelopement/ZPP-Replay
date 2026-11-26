#!/usr/bin/env bash
# Boots a Paper server with the core, PacketEvents and ZPP-Replay jars, then checks the log for the boot outcome.
# Usage: tools/boot-smoke.sh <paper-version> <port>
# Required env: SMOKE_DIR (work dir, outside the repo and test_servers/), PACKETEVENTS_JAR, CORE_JAR, REPLAY_JAR.
# Optional env: JAVA (default: java). Prints BOOT_SMOKE PASS|FAIL lines; exit 0 on PASS, 1 on FAIL.
set -euo pipefail

usage() {
    echo "usage: $0 <paper-version> <port>" >&2
    exit 2
}

[[ $# -eq 2 ]] || usage
version=$1
port=$2
[[ "$port" =~ ^[0-9]+$ ]] || usage
for var in SMOKE_DIR PACKETEVENTS_JAR CORE_JAR REPLAY_JAR; do
    [[ -n "${!var:-}" ]] || { echo "$var is required" >&2; exit 2; }
done
case "$SMOKE_DIR" in
    *test_servers*) echo "SMOKE_DIR must not be inside test_servers/" >&2; exit 2 ;;
esac
JAVA=${JAVA:-java}

fail() {
    echo "BOOT_SMOKE FAIL $version $*"
    exit 1
}

sha256_of() {
    shasum -a 256 "$1" | cut -d' ' -f1
}

# Paper build: the latest build of the version, downloaded once and checked against its sha256.
version_dir="$SMOKE_DIR/$version"
mkdir -p "$version_dir"
meta=$(curl -fsS --max-time 60 "https://fill.papermc.io/v3/projects/paper/versions/$version/builds/latest") \
    || fail "could not read the Paper build list"
url=$(jq -r '.downloads["server:default"].url' <<<"$meta")
sha=$(jq -r '.downloads["server:default"].checksums.sha256' <<<"$meta")
paper_jar="$version_dir/paper.jar"
if [[ ! -f "$paper_jar" || "$(sha256_of "$paper_jar")" != "$sha" ]]; then
    curl -fsS --max-time 900 -o "$paper_jar.part" "$url" || fail "Paper download failed"
    [[ "$(sha256_of "$paper_jar.part")" == "$sha" ]] || { rm -f "$paper_jar.part"; fail "Paper sha256 mismatch"; }
    mv "$paper_jar.part" "$paper_jar"
fi

# Fresh server folder and plugins/ each run.
run_dir="$version_dir/run"
rm -rf "$run_dir"
mkdir -p "$run_dir/plugins"
printf 'eula=true\n' > "$run_dir/eula.txt"
cat > "$run_dir/server.properties" <<EOF
online-mode=false
level-type=minecraft\:flat
spawn-protection=0
generate-structures=false
view-distance=4
simulation-distance=4
server-port=$port
pause-when-empty-seconds=-1
EOF
cp "$PACKETEVENTS_JAR" "$CORE_JAR" "$REPLAY_JAR" "$run_dir/plugins/"

# Console input comes from a fifo so the script can send `stop`. The fd is read-write so neither end blocks.
fifo="$run_dir/stdin.fifo"
log="$run_dir/server.log"
mkfifo "$fifo"
exec 3<>"$fifo"
(
    cd "$run_dir"
    exec "$JAVA" -Xmx2G -jar "$paper_jar" --nogui <&3 >"$log" 2>&1
) &
pid=$!
trap 'kill -9 "$pid" 2>/dev/null || true' EXIT

start=$(date +%s)
stop_sent=0
stop_at=0
while kill -0 "$pid" 2>/dev/null; do
    now=$(date +%s)
    if [[ $stop_sent -eq 0 ]] && { grep -q 'Done (' "$log" 2>/dev/null || (( now - start >= 180 )); }; then
        echo stop >&3
        stop_sent=1
        stop_at=$now
    fi
    if [[ $stop_sent -eq 1 ]] && (( now - stop_at >= 60 )); then
        kill -9 "$pid" 2>/dev/null || true
        break
    fi
    sleep 1
done
wait "$pid" 2>/dev/null || true
exec 3>&-
echo "log $log"

if grep -q 'Enabling ZonePracticePro' "$log"; then
    echo "core=enabled (informational)"
else
    echo "core=not-enabled (informational)"
fi

# Pass conditions. Lines that name the plugin with ERROR or Exception, or a config load failure, fail the run.
config_failures=$(grep 'Cannot load plugins/ZPPReplay' "$log" || true)
if ! grep -q '\[ZPPReplay\] Enabling ZPPReplay' "$log"; then
    fail "no '[ZPPReplay] Enabling ZPPReplay' line"
fi
replay_errors=$(grep 'ZPPReplay' "$log" | grep -E 'ERROR|Exception' || true)
if [[ -n "$replay_errors$config_failures" ]]; then
    fail "replay errors: $(head -1 <<<"$replay_errors$config_failures")"
fi
echo "BOOT_SMOKE PASS $version enabled, no ERROR or exception lines naming ZPPReplay"
