.PHONY: refresh restart logs stop clean help

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
	@echo "  make refresh  - Remove GH data, rebuild and restart containers"
	@echo "  make restart  - Restart containers keeping GH data"
	@echo "  make stop     - Stop all containers"
	@echo "  make logs     - Follow container logs"
	@echo "  make clean    - Remove everything (containers, volumes, GH data)"
	@echo "  make help     - Show this help message"