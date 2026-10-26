FROM sbtscala/scala-sbt:eclipse-temurin-17.0.13_11_1.10.7_3.3.4 AS builder

WORKDIR /workspace

COPY project project
COPY build.sbt .

# Resolve plugins and dependencies in a cacheable layer.
RUN sbt -batch update

COPY conf conf
COPY app app

# Compile the Play application into a self-contained distribution.
RUN sbt -batch dist

FROM eclipse-temurin:17-jre

WORKDIR /app

COPY --from=builder /workspace/target/universal/legally-distinct-pictionary-0.1.0-SNAPSHOT.zip /tmp/app.zip

RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && unzip /tmp/app.zip -d /app \
    && rm /tmp/app.zip \
    && apt-get purge -y unzip \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*

EXPOSE 10000

CMD ["sh", "-c", "/app/legally-distinct-pictionary-0.1.0-SNAPSHOT/bin/legally-distinct-pictionary"]
