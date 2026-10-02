# Build stage: Maven and Node come ready-made from their official images, rather than apt
# installing them (Ubuntu's maven package pulls in a second JDK and npm hundreds of node-*
# packages: ~1.4 GB and several minutes per uncached build).
FROM maven:3.9-eclipse-temurin-17 AS build

# npm is needed by the Maven build (exec-maven-plugin bundles the web frontend)
COPY --from=node:20-bookworm-slim /usr/local/bin/node /usr/local/bin/node
COPY --from=node:20-bookworm-slim /usr/local/lib/node_modules /usr/local/lib/node_modules
RUN ln -s ../lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm && node --version && npm --version

WORKDIR /app
COPY . /app/

# Maven and npm downloads are cached across builds (and across checkouts on the same Docker host)
RUN --mount=type=cache,target=/root/.m2 \
    --mount=type=cache,target=/root/.npm \
    mvn -B clean package -DskipTests

# Runtime stage: only a JRE and the shaded jar
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY --from=build /app/target/railway_routing-*.jar /app/target/

# Copy docker-specific config files (they're in docker/ relative to context)
COPY docker/config.yml /app/config.yml
COPY docker/custom_models /app/custom_models

RUN mkdir -p /app/data /app/graph-cache

EXPOSE 8989

CMD ["sh", "-c", "\
    echo 'Checking for existing graph...' && \
    if [ ! -d \"/app/data/filtered_train.osm-gh\" ]; then \
        echo 'Importing graph from OSM file...'; \
        java -Xmx48g -jar target/railway_routing-*.jar import config.yml; \
    else \
        echo 'Graph already exists, skipping import.'; \
    fi && \
    echo 'Starting server...' && \
    java -Xmx32g -jar target/railway_routing-*.jar serve config.yml \
"]
