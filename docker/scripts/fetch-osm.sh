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

# Downloads a region unless the cached copy is already as new as Geofabrik's. Goes through a
# .part file so an interrupted download is never mistaken for a complete, up-to-date one.
download_region() {
    local region="$1"
    local dest
    dest="$(region_pbf "$region")"
    mkdir -p "$(dirname "$dest")"
    local condition=()
    if [[ -f "$dest" ]]; then
        condition=(-z "$dest")
    fi
    echo "==> Downloading $region" >&2
    local status
    status="$(curl -fL -R --progress-bar "${condition[@]}" -o "$dest.part" -w '%{http_code}' \
        "https://download.geofabrik.de/${region}-latest.osm.pbf")"
    if [[ "$status" == "304" ]]; then
        rm -f "$dest.part"
        echo "==> $region is already up to date" >&2
    else
        mv -f "$dest.part" "$dest"
    fi
}

filter_region() {
    local input="$1"
    local output="$2"
    echo "==> Filtering $(basename "$input") -> $(basename "$output")" >&2
    osmium tags-filter --overwrite -o "$output" "$input" nw/railway
}

case "$MODE" in
    dev)
        DEV_REGION="${DEV_REGION:-europe/norway}"
        mkdir -p "$WORLD_DIR" "$OSM_DIR"
        download_region "$DEV_REGION"
        # always re-filtered: OUTPUT is shared by whichever DEV_REGION was fetched last
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
        download_region "${regions[0]}" &
        download_pid=$!
        filtered_files=()
        for i in "${!regions[@]}"; do
            region="${regions[$i]}"
            wait "$download_pid"
            if (( i + 1 < ${#regions[@]} )); then
                download_region "${regions[$((i + 1))]}" &
                download_pid=$!
            fi
            pbf="$(region_pbf "$region")"
            filtered="$FILTERED_DIR/$(echo "$region" | tr '/' '_').osm.pbf"
            if [[ -f "$filtered" && "$filtered" -nt "$pbf" ]]; then
                echo "==> $(basename "$filtered") is already up to date" >&2
            else
                filter_region "$pbf" "$filtered"
            fi
            filtered_files+=("$filtered")
        done

        echo "==> Merging ${#filtered_files[@]} region(s) -> $(basename "$OUTPUT")"
        osmium merge --overwrite -o "$OUTPUT" "${filtered_files[@]}"
        ;;
    *)
        echo "usage: $0 <dev|prod>" >&2
        exit 1
        ;;
esac

echo "==> Done: $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
