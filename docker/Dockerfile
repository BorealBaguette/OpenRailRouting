FROM eclipse-temurin:17-jdk

# Install dependencies
RUN apt-get update && \
    apt-get install -y maven nodejs npm && \
    rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy the OpenRailRouting source (context is parent dir, so . is the root)
COPY . /app/

# Copy docker-specific config files (they're in docker/ relative to context)
COPY docker/config.yml /app/config.yml
COPY docker/custom_models /app/custom_models

# Build the application
RUN mvn clean package -DskipTests && \
    mkdir -p /app/data /app/graph-cache

EXPOSE 8989

CMD ["sh", "-c", "\
    echo 'Checking for existing graph...' && \
    if [ ! -d \"/app/data/filtered_train.osm-gh\" ]; then \
        echo 'Importing graph from OSM file...'; \
        java -Xmx32g -jar target/railway_routing-*.jar import config.yml; \
    else \
        echo 'Graph already exists, skipping import.'; \
    fi && \
    echo 'Starting server...' && \
    java -Xmx32g -jar target/railway_routing-*.jar serve config.yml \
"]