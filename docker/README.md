# Graphhopper Router Stub

This project is a stub intended to experiment with replacing Trainlog's default OSRM train routing allowing the use of router profiles. Built with Docker

## What it does

* Includes basic routing profiles
* Processes OpenStreetMap `.osm.pbf` files with the custom logic
* Serves the result, including a basic frontend 
* Intended for testing or prototyping alternative routing setups

## How to use

1. Put your `.osm.pbf` file into the `osm/` directory
   Example: `osm/filtered_train.osm.pbf`

2. Make sure the path to your file is correctly set in `config.yml`

3. Run the setup using Docker Compose:

   ```bash
   docker compose up
   ```
4. Go to http://localhost:8989 to try the routing