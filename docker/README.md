# Graphhopper Router Stub

This project is a stub intended to experiment with replacing Trainlog's default OSRM train routing allowing the use of router profiles. Built with Docker

## What it does

* Includes basic routing profiles
* Processes OpenStreetMap `.osm.pbf` files with the custom logic
* Serves the result, including a basic frontend 
* Intended for testing or prototyping alternative routing setups

## How to use

From the repo root, use the `Makefile`:

```bash
make dev   # start with a small OSM extract (France) — fast iteration
make prod  # start with the full worldwide rail network (see docker/regions.wanted)
```

Both fetch OSM data from Geofabrik and filter it down to railways with `osmium` **only if
`docker/osm/filtered_train.osm.pbf` doesn't already exist** — otherwise they just (re)start
the stack against the data already on disk, without refetching or reimporting. To force a
fresh download and reimport, use `make dev-refresh` / `make prod-refresh` instead.

`osmium-tool` and `wget` must be installed on the host (the fetch/filter step runs outside
Docker, before the data is mounted into the container).

`make dev`/`make dev-refresh` use `europe/france` by default; override with
`DEV_REGION=europe/belgium make dev-refresh`. `make prod`/`make prod-refresh` download every
region listed in `docker/regions.wanted` (worldwide by default).

### Manual / custom extracts

If you'd rather provide your own filtered `.osm.pbf` (e.g. a custom bounding box):

1. Put your file into `osm/filtered_train.osm.pbf` directly
2. Make sure the path is correctly set in `config.yml`
3. Run `make refresh` (or `docker compose up` directly) to build and start the stack

Go to http://localhost:8989 to try the routing.