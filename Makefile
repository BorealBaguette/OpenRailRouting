.PHONY: dev dev-refresh prod prod-refresh refresh restart logs stop clean help build stage stage-dev stage-prod promote-staged

OSM_DATA := docker/osm/filtered_train.osm.pbf

# Dev: ensure a small OSM extract (default: France) is present and the stack is up.
# Does not re-fetch or reimport if data already exists — use 'make dev-refresh' for that.
dev:
	@if [ -f $(OSM_DATA) ]; then \
		echo "OSM data already present ($(OSM_DATA)) - starting without refetching."; \
		echo "Use 'make dev-refresh' to force a fresh download and reimport."; \
		$(MAKE) restart; \
	else \
		$(MAKE) dev-refresh; \
	fi

# Dev refresh: force a fresh dev OSM extract download, reimport and rebuild
dev-refresh:
	@echo "📥 Fetching dev OSM extract..."
	docker/scripts/fetch-osm.sh dev
	$(MAKE) refresh

# Prod: ensure the full worldwide OSM extract is present and the stack is up.
# Does not re-fetch or reimport if data already exists — use 'make prod-refresh' for that.
prod:
	@if [ -f $(OSM_DATA) ]; then \
		echo "OSM data already present ($(OSM_DATA)) - starting without refetching."; \
		echo "Use 'make prod-refresh' to force a fresh download and reimport."; \
		$(MAKE) restart; \
	else \
		$(MAKE) prod-refresh; \
	fi

# Prod refresh: force a fresh worldwide OSM extract download, reimport and rebuild
prod-refresh:
	@echo "📥 Fetching prod OSM extract..."
	docker/scripts/fetch-osm.sh prod
	$(MAKE) refresh

# Refresh: Remove GraphHopper data and rebuild containers
refresh:
	@echo "🔄 Refreshing: Removing GraphHopper data and rebuilding..."
	sudo rm -rf docker/osm/filtered_train.osm-gh
	docker compose -f docker/docker-compose.yml down
	docker compose -f docker/docker-compose.yml up --build -d
	@echo "✅ Refresh complete! Following logs..."
	docker compose -f docker/docker-compose.yml logs -f

# Restart: Restart containers without removing GraphHopper data
restart:
	@echo "🔄 Restarting containers (keeping GraphHopper data)..."
	docker compose -f docker/docker-compose.yml down
	docker compose -f docker/docker-compose.yml up -d
	@echo "✅ Restart complete! Following logs..."
	docker compose -f docker/docker-compose.yml logs -f

# Build: rebuild the image only, without touching the running/serving container.
# Safe to run at any time - a running container keeps using its already-started image
# until you explicitly restart it (refresh/restart/promote-staged).
build:
	@echo "🔨 Building image (live service, if running, is unaffected)..."
	docker compose -f docker/docker-compose.yml build

# Stage: reimport OSM data into a separate directory (docker/osm-staging/), using the
# currently built image, WITHOUT touching or interrupting the live/serving container.
# Run 'make build' first if you need the new image's code, then one of these, then
# 'make promote-staged' to do the actual (brief, seconds-long) cutover.
#   make stage        - reimport from the OSM data already on disk
#   make stage-dev     - fetch a fresh dev extract (France) into staging first
#   make stage-prod    - fetch a fresh worldwide extract into staging first
stage:
	docker/scripts/reimport-staged.sh

stage-dev:
	docker/scripts/reimport-staged.sh dev

stage-prod:
	docker/scripts/reimport-staged.sh prod

# Promote: swap the staged graph into place and restart. This is the only step that
# actually interrupts serving, and it's brief (a container restart, not a reimport) -
# the previous graph is kept at docker/osm/filtered_train.osm-gh.old for manual rollback
# until you're happy with the new one and remove it yourself. Uses a throwaway container
# (not sudo) for the root-owned graph-cache directories, since GraphHopper writes them
# as root inside the container.
promote-staged:
	@if [ ! -d docker/osm-staging/filtered_train.osm-gh ]; then \
		echo "error: no staged graph found - run 'make stage' (or stage-dev/stage-prod) first" >&2; \
		exit 1; \
	fi
	docker compose -f docker/docker-compose.yml down
	docker run --rm -v $(CURDIR)/docker/osm:/live -v $(CURDIR)/docker/osm-staging:/staged alpine sh -c '\
		rm -rf /live/filtered_train.osm-gh.old && \
		if [ -d /live/filtered_train.osm-gh ]; then mv /live/filtered_train.osm-gh /live/filtered_train.osm-gh.old; fi && \
		mv /staged/filtered_train.osm-gh /live/filtered_train.osm-gh && \
		mv -f /staged/filtered_train.osm.pbf /live/filtered_train.osm.pbf \
	'
	rm -rf docker/osm-staging
	docker compose -f docker/docker-compose.yml up -d
	@echo "✅ Promoted. Previous graph kept at docker/osm/filtered_train.osm-gh.old until you remove it."
	docker compose -f docker/docker-compose.yml logs -f

# Stop containers
stop:
	@echo "⏹️  Stopping containers..."
	docker compose -f docker/docker-compose.yml down

# View logs
logs:
	docker compose -f docker/docker-compose.yml logs -f

# Clean: Remove containers, volumes, and GraphHopper data
clean:
	@echo "🧹 Cleaning up everything..."
	docker compose -f docker/docker-compose.yml down -v
	sudo rm -rf docker/osm/filtered_train.osm-gh
	@echo "✅ Clean complete!"

# Help
help:
	@echo "Available commands:"
	@echo "  make dev          - Start with dev OSM extract (France), fetching only if missing"
	@echo "  make dev-refresh  - Force a fresh dev OSM extract download and reimport"
	@echo "  make prod         - Start with full worldwide OSM extract, fetching only if missing"
	@echo "  make prod-refresh - Force a fresh worldwide OSM extract download and reimport"
	@echo "  make refresh      - Remove GH data, rebuild and restart containers (no refetch)"
	@echo "  make restart      - Restart containers keeping GH data"
	@echo ""
	@echo "  Zero-downtime reimport (live service keeps serving during the reimport):"
	@echo "  make build         - Rebuild the image only (safe, doesn't touch the running container)"
	@echo "  make stage         - Reimport existing OSM data into a staging dir, live service unaffected"
	@echo "  make stage-dev     - Fetch a fresh dev extract into staging, then reimport"
	@echo "  make stage-prod    - Fetch a fresh worldwide extract into staging, then reimport"
	@echo "  make promote-staged - Swap the staged graph into place (brief restart, seconds not minutes)"
	@echo "  make stop         - Stop all containers"
	@echo "  make logs         - Follow container logs"
	@echo "  make clean        - Remove everything (containers, volumes, GH data)"
	@echo "  make help         - Show this help message"