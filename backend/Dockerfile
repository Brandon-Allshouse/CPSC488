# Builds and runs the backend. Used by docker-compose.yml; you normally don't run this directly.
# Tool versions are pinned here so every machine builds with the same Maven and Java.

# ---- Build stage: compile with Maven ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

# Download dependencies first, in their own layer. Docker reuses this layer until pom.xml
# changes, so editing Java code doesn't re-download every library.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Compile, then copy the runtime libraries next to the classes. Tests aren't run here; run them
# with `docker compose run --rm backend-tests` (see README) and in CI.
COPY src ./src
RUN mvn -B -q compile dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/lib

# ---- Run stage: only the Java runtime and the compiled app, no Maven or source code ----
FROM eclipse-temurin:25-jre
# Run as an unprivileged user rather than root, so a bug in the app can't take over the container.
RUN useradd --system --create-home app
WORKDIR /app
COPY --from=build /app/target/classes ./classes
COPY --from=build /app/target/lib ./lib
USER app
EXPOSE 7070
CMD ["java", "-cp", "classes:lib/*", "edu.cpsc488.brainfeed.App"]
