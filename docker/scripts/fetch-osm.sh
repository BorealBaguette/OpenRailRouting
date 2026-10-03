#!/usr/bin/env bash
# Fetches and filters OSM rail data for OpenRailRouting.
#
# Usage:
#   fetch-osm.sh dev    # single small region, fast iteration (default: europe/norway)
#   fetch-osm.sh prod   # every region in regions.wanted, merged (default: worldwide)
#
# Requires `curl` and `osmium` (osmium-tool) on the host. Output defaults to
# docker/osm/filtered_train.osm.pbf (matching config.yml's datareader.file) but can be
# redirected with OUTPUT=/some/other/path.osm.pbf - e.g. to stage a reimport into a
# separate directory without touching the live service's data (see reimport-staged.sh).
# Downloaded/filtered per-region files are always cached under docker/osm/ regardless of
# OUTPUT, so staging doesn't re-download or re-filter data that's already up to date.
set -euo pipefail

MODE="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(dirname "$SCRIPT_DIR")"
OSM_DIR="$DOCKER_DIR/osm"
WORLD_DIR="$OSM_DIR/world"
FILTERED_DIR="$OSM_DIR/filtered"
OUTPUT="${OUTPUT:-$OSM_DIR/filtered_train.osm.pbf}"

for cmd in curl osmium; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "error: '$cmd' is required on the host but was not found in PATH" >&2
        exit 1
    fi
done

mkdir -p "$(dirname "$OUTPUT")"

region_pbf() {
    echo "$WORLD_DIR/$(echo "$1" | tr '/' '_')-latest.osm.pbf"
}

region_url() {
    echo "https://download.geofabrik.de/${1}-latest.osm.pbf"
}

human() {
    numfmt --to=iec --suffix=B --format=%.1f "$1"
}

# Downloads a region unless the cached copy is already as new as Geofabrik's. Runs in the
# background, so it's silent; its one-line result is left in a .msg file for wait_for_download
# to print. Goes through a .part file so an interrupted download is never mistaken for a
# complete, up-to-date one.
download_region() {
    local region="$1"
    local dest
    dest="$(region_pbf "$region")"
    mkdir -p "$(dirname "$dest")"
    local condition=()
    if [[ -f "$dest" ]]; then
        condition=(-z "$dest")
    fi
    local start status
    start=$(date +%s)
    status="$(curl -fsSL -R "${condition[@]}" -o "$dest.part" -w '%{http_code}' "$(region_url "$region")")"
    if [[ "$status" == "304" ]]; then
        rm -f "$dest.part"
        echo "==> $region: download already up to date" > "$dest.msg"
    else
        mv -f "$dest.part" "$dest"
        local size seconds
        size=$(stat -c %s "$dest")
        seconds=$(( $(date +%s) - start ))
        (( seconds > 0 )) || seconds=1
        echo "==> $region: downloaded $(human "$size") in ${seconds}s ($(human $(( size / seconds )))/s)" > "$dest.msg"
    fi
}

# Waits for a background download, showing its progress meanwhile (only when there's nothing
# else to show, i.e. filtering has caught up with downloading), then prints its result.
wait_for_download() {
    local pid="$1" region="$2"
    local msg
    msg="$(region_pbf "$region").msg"
    if kill -0 "$pid" 2>/dev/null; then
        local part total previous=0 now rate eta
        part="$(region_pbf "$region").part"
        total=$(curl -sIL "$(region_url "$region")" | tr -d '\r' \
            | awk 'tolower($1) == "content-length:" { n = $2 } END { print n + 0 }')
        while kill -0 "$pid" 2>/dev/null; do
            now=$(stat -c %s "$part" 2>/dev/null || echo 0)
            rate=$(( now > previous ? now - previous : 0 ))
            eta="--"
            if (( rate > 0 && total > now )); then
                eta="$(( (total - now) / rate / 60 ))m$(( (total - now) / rate % 60 ))s"
            fi
            if (( now == 0 )); then
                printf '\r    waiting for %s: checking for a newer version     ' "$region" >&2
            else
                printf '\r    waiting for %s: %s of %s, %s/s, ETA %s     ' "$region" \
                    "$(human "$now")" "$(human "$total")" "$(human "$rate")" "$eta" >&2
            fi
            previous=$now
            sleep 1
        done
        printf '\r\033[K' >&2
    fi
    wait "$pid"
    cat "$msg" >&2
    rm -f "$msg"
}

filter_region() {
    local input="$1"
    local output="$2"
    osmium tags-filter --progress --overwrite -o "$output" "$input" nw/railway
}

case "$MODE" in
    dev)
        DEV_REGION="${DEV_REGION:-europe/norway}"
        mkdir -p "$WORLD_DIR" "$OSM_DIR"
        download_region "$DEV_REGION" &
        wait_for_download $! "$DEV_REGION"
        # always re-filtered: OUTPUT is shared by whichever DEV_REGION was fetched last
        echo "==> $DEV_REGION: filtering" >&2
        filter_region "$(region_pbf "$DEV_REGION")" "$OUTPUT"
        ;;
    prod)
        REGIONS_FILE="${REGIONS_FILE:-$DOCKER_DIR/regions.wanted}"
        mkdir -p "$WORLD_DIR" "$FILTERED_DIR" "$OSM_DIR"
        mapfile -t regions < <(grep -vE '^[[:space:]]*(#|$)' "$REGIONS_FILE")
        if [[ ${#regions[@]} -eq 0 ]]; then
            echo "error: no regions found in $REGIONS_FILE" >&2
            exit 1
        fi

        # Downloading is network-bound and filtering CPU/disk-bound, so the next region
        # downloads in the background while the current one is filtered.
        trap 'jobs -p | xargs -r kill 2>/dev/null' EXIT
        count=${#regions[@]}
        echo "==> $count region(s); each one downloads in the background while the previous is filtered" >&2
        download_region "${regions[0]}" &
        download_pid=$!
        filtered_files=()
        for i in "${!regions[@]}"; do
            region="${regions[$i]}"
            wait_for_download "$download_pid" "$region"
            next=""
            if (( i + 1 < count )); then
                next="${regions[$((i + 1))]}"
                download_region "$next" &
                download_pid=$!
            fi
            pbf="$(region_pbf "$region")"
            filtered="$FILTERED_DIR/$(echo "$region" | tr '/' '_').osm.pbf"
            if [[ -f "$filtered" && "$filtered" -nt "$pbf" ]]; then
                echo "==> [$((i + 1))/$count] $region: filtered file already up to date" >&2
            else
                echo "==> [$((i + 1))/$count] $region: filtering${next:+ (downloading $next in the background)}" >&2
                filter_region "$pbf" "$filtered"
            fi
            filtered_files+=("$filtered")
        done

        echo "==> Merging $count region(s) -> $(basename "$OUTPUT")" >&2
        osmium merge --overwrite -o "$OUTPUT" "${filtered_files[@]}"
        ;;
    *)
        echo "usage: $0 <dev|prod>" >&2
        exit 1
        ;;
esac

echo "==> Done: $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
