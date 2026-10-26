# Research Bank API: one image for every Kubernetes variant (V0-V3).
#
# Build (from the project root; downloads Maven and the dependencies through the Maven Wrapper):
#   docker build -t bank-api:dev .
#
# Run (the image contains no secrets; PostgreSQL is configured only through environment variables):
#   docker run --rm -p 8080:8080 -p 8081:8081 \
#     -e BANK_DB_URL=jdbc:postgresql://<db-host>:5432/bank_test \
#     -e BANK_DB_USER=<user> -e BANK_DB_PASSWORD=<password> bank-api:dev
#
# Environment variables
#   BANK_DB_URL, BANK_DB_USER, BANK_DB_PASSWORD   PostgreSQL connection (required; there is no default password)
#   BANK_DB_CONNECTION_TIMEOUT_MS                 wait for a pooled DB connection, default 3000
#   SERVER_PORT / MANAGEMENT_SERVER_PORT          user API port (8080) / internal management port (8081)
#   INSTANCE_ID                                   instance name for logs and X-Instance-Id (default: container hostname,
#                                                 i.e. the Pod name in Kubernetes)
#   SPRING_PROFILES_ACTIVE=research               research-only fault endpoint, never in the normal deployment
#   LOGGING_LEVEL_BANK_OPERATIONS                 INFO (default) or WARN to silence per-request operation log lines
#   JAVA_TOOL_OPTIONS                             extra JVM options
# Flyway creates the schema and the test accounts on the first start; a restart never resets the data.

# ---- build: JDK 17 (the project's Java level) + the project's Maven Wrapper; tests need PostgreSQL and run separately
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /workspace
# The wrapper and pom first, so that the dependency layer is reused while only the sources change.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -DskipTests package && cp target/*-SNAPSHOT.jar app.jar

# ---- runtime: JRE only, non-root user
FROM eclipse-temurin:17-jre-jammy
RUN groupadd --system bank && useradd --system --gid bank --no-create-home --shell /usr/sbin/nologin bank
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
USER bank
EXPOSE 8080 8081
# Heap follows the container memory limit (important for the equal requests/limits of the K8s variants).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
