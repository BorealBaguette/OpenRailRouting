#!/usr/bin/env bash
# Rebuilds the GraphHopper graph into a separate staging directory, without touching the
# live service's data or interrupting it. The live container keeps serving throughout -
# there's no reimport downtime, only a brief restart once you run `make promote-staged`.
#
# Usage:
#   docker/scripts/reimport-staged.sh          # reimport from the OSM data already on disk
#   docker/scripts/reimport-staged.sh dev      # also fetch a fresh dev extract into staging first
#   docker/scripts/reimport-staged.sh prod     # also fetch a fresh worldwide extract into staging first
#
# Requires the openrailrouting image to already be built (docker compose build), and the
# corresponding Java code/config to be what you want imported - this script only handles
# data, not rebuilding the image.
set -euo pipefail

MODE="${1:-}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$(dirname "$SCRIPT_DIR")"
STAGING_DIR="$DOCKER_DIR/osm-staging"
LIVE_PBF="$DOCKER_DIR/osm/filtered_train.osm.pbf"
STAGING_PBF="$STAGING_DIR/filtered_train.osm.pbf"
IMAGE="${IMAGE:-docker-openrailrouting}"

mkdir -p "$STAGING_DIR"

if [[ -n "$MODE" ]]; then
    echo "==> Fetching fresh OSM data into staging ($MODE)"
    OUTPUT="$STAGING_PBF" "$SCRIPT_DIR/fetch-osm.sh" "$MODE"
else
    if [[ ! -f "$LIVE_PBF" ]]; then
        echo "error: $LIVE_PBF not found, and no fetch mode given (dev|prod)" >&2
        exit 1
    fi
    echo "==> Reusing OSM data already on disk ($LIVE_PBF)"
    cp "$LIVE_PBF" "$STAGING_PBF"
fi

# A leftover graph-cache dir from a previous staged run is root-owned (GraphHopper writes
# it as root inside the container), so a throwaway container is used to remove it rather
# than requiring host sudo.
if [[ -d "$STAGING_DIR/filtered_train.osm-gh" ]]; then
    docker run --rm -v "$STAGING_DIR:/data" alpine rm -rf /data/filtered_train.osm-gh
fi

echo "==> Importing into staging graph cache - this can take a while, the live service is unaffected"
docker run --rm \
    -v "$STAGING_DIR:/app/data" \
    -v "$DOCKER_DIR/config.yml:/app/config.yml:ro" \
    -v "$DOCKER_DIR/custom_models:/app/custom_models:ro" \
    "$IMAGE" \
    sh -c 'java -Xmx32g -jar target/railway_routing-*.jar import config.yml'

echo "==> Staging import complete: $STAGING_DIR/filtered_train.osm-gh"
echo "    Run 'make promote-staged' to switch the live service over to it."
