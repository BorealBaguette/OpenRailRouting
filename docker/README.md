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
make dev   # start with a small OSM extract (Norway) — fast iteration
make prod  # start with the full worldwide rail network (see docker/regions.wanted)
```

Both fetch OSM data from Geofabrik and filter it down to railways with `osmium` **only if
`docker/osm/filtered_train.osm.pbf` doesn't already exist** — otherwise they just (re)start
the stack against the data already on disk, without refetching or reimporting. To force a
fresh download and reimport, use `make dev-refresh` / `make prod-refresh` instead.

`osmium-tool` and `wget` must be installed on the host (the fetch/filter step runs outside
Docker, before the data is mounted into the container).

`make dev`/`make dev-refresh` use `europe/norway` by default; override with
`DEV_REGION=europe/belgium make dev-refresh`. `make prod`/`make prod-refresh` download every
region listed in `docker/regions.wanted` (worldwide by default).

### Zero-downtime reimport (production)

`make refresh`/`make dev-refresh`/`make prod-refresh` all stop the service before reimporting,
so it's offline for the whole reimport (which can take a long time on the worldwide dataset).
For a running deployment, reimport into a separate staging directory instead, while the live
service keeps serving throughout, then do a brief (seconds, not minutes) cutover:

```bash
make build          # rebuild the image only - never touches the running container
make stage-prod      # fetch + reimport into docker/osm-staging/, live service unaffected
                      # (or 'make stage' to reimport existing data without refetching,
                      # or 'make stage-dev' for the dev extract)
make promote-staged  # swap the staged graph into place - the actual (brief) cutover
```

The previous graph is kept at `docker/osm/filtered_train.osm-gh.old` after promoting, for
manual rollback, until you remove it yourself once you're happy with the new one.

### Network

The router joins the `trainlog_network` Docker network, which it shares with the
`services_proxy` nginx. nginx reaches it by container name on its container port
(`train-gh.srv.trainlog.me` → `train_routing_gh:8989`). `make refresh`/`restart`/`promote-staged`
create the network first if this machine doesn't have it (`make network`). Plain
`docker compose up` expects it to exist already.

### Running a second instance side by side

To try a new version next to the running one, use a separate checkout, e.g.
`git worktree add ../OpenRailRouting-next <branch>`. That gives it its own data directories. In
that checkout, create `docker/.env` (gitignored):

```
COMPOSE_PROJECT_NAME=orr-next
ORR_IMAGE=orr-next-openrailrouting
ORR_CONTAINER_NAME=train_routing_gh_next
ORR_PORT=8991
```

All four lines are needed:
- `COMPOSE_PROJECT_NAME`: by default both checkouts are the project `docker` (after the
  folder name). Without this, `up` in one would replace the other's container.
- `ORR_IMAGE`: otherwise building one overwrites the image the other restarts from.
  `reimport-staged.sh` picks it up too.
- `ORR_CONTAINER_NAME`: container names are global, and the name is what nginx uses to find the
  router.
- `ORR_PORT`: the host port. It only matters for direct access; nginx goes through the
  network.

To expose it through nginx, add a line to `nginx.conf` in the infra repo, next to `train-gh`:

```
if ($service = "train-gh-next") { set $target "train_routing_gh_next"; set $port 8989; }
```

It's then reachable at `train-gh-next.srv.trainlog.me`. Trainlog can be pointed at it with
`NEW_TRAIN_ROUTER_URL`. Each instance starts Java with `-Xmx32g`, so check the host has memory
for both before loading the world graph twice.

### Manual / custom extracts

If you'd rather provide your own filtered `.osm.pbf` (e.g. a custom bounding box):

1. Put your file into `osm/filtered_train.osm.pbf` directly
2. Make sure the path is correctly set in `config.yml`
3. Run `make refresh` (or `docker compose up` directly) to build and start the stack

Go to http://localhost:8989 to try the routing.