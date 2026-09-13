#!/usr/bin/env bash
# Fetches and filters OSM rail data for OpenRailRouting.
#
# Usage:
#   fetch-osm.sh dev    # single small region, fast iteration (default: europe/france)
#   fetch-osm.sh prod   # every region in regions.wanted, merged (default: worldwide)
#
# Requires `wget` and `osmium` (osmium-tool) on the host. Output always lands
# at docker/osm/filtered_train.osm.pbf, matching config.yml's datareader.file.
set -euo pipefail

MODE="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(dirname "$SCRIPT_DIR")"
OSM_DIR="$DOCKER_DIR/osm"
WORLD_DIR="$OSM_DIR/world"
FILTERED_DIR="$OSM_DIR/filtered"
OUTPUT="$OSM_DIR/filtered_train.osm.pbf"

for cmd in wget osmium; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "error: '$cmd' is required on the host but was not found in PATH" >&2
        exit 1
    fi
done

download_region() {
    local region="$1"
    local dest="$WORLD_DIR/$(echo "$region" | tr '/' '_')-latest.osm.pbf"
    mkdir -p "$(dirname "$dest")"
    echo "==> Downloading $region" >&2
    wget -N -q --show-progress -O "$dest" "https://download.geofabrik.de/${region}-latest.osm.pbf"
    echo "$dest"
}

filter_region() {
    local input="$1"
    local output="$2"
    echo "==> Filtering $(basename "$input") -> $(basename "$output")" >&2
    osmium tags-filter --overwrite -o "$output" "$input" nw/railway
}

case "$MODE" in
    dev)
        DEV_REGION="${DEV_REGION:-europe/france}"
        mkdir -p "$WORLD_DIR" "$OSM_DIR"
        pbf="$(download_region "$DEV_REGION")"
        filter_region "$pbf" "$OUTPUT"
        ;;
    prod)
        REGIONS_FILE="${REGIONS_FILE:-$DOCKER_DIR/regions.wanted}"
        mkdir -p "$WORLD_DIR" "$FILTERED_DIR" "$OSM_DIR"
        filtered_files=()
        while IFS= read -r region; do
            [[ -z "$region" || "$region" == \#* ]] && continue
            pbf="$(download_region "$region")"
            filtered="$FILTERED_DIR/$(echo "$region" | tr '/' '_').osm.pbf"
            filter_region "$pbf" "$filtered"
            filtered_files+=("$filtered")
        done < "$REGIONS_FILE"

        if [[ ${#filtered_files[@]} -eq 0 ]]; then
            echo "error: no regions found in $REGIONS_FILE" >&2
            exit 1
        fi

        echo "==> Merging ${#filtered_files[@]} region(s) -> $(basename "$OUTPUT")"
        osmium merge --overwrite -o "$OUTPUT" "${filtered_files[@]}"
        ;;
    *)
        echo "usage: $0 <dev|prod>" >&2
        exit 1
        ;;
esac

echo "==> Done: $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
