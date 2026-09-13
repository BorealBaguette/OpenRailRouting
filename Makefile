.PHONY: dev dev-refresh prod prod-refresh refresh restart logs stop clean help

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
	@echo "  make stop         - Stop all containers"
	@echo "  make logs         - Follow container logs"
	@echo "  make clean        - Remove everything (containers, volumes, GH data)"
	@echo "  make help         - Show this help message"