FROM sbtscala/scala-sbt:eclipse-temurin-17.0.13_11_1.10.7_3.3.4

WORKDIR /workspace

COPY project project
COPY build.sbt .

# Resolve build plugins and dependencies in a cacheable layer.
RUN sbt -batch update

COPY conf conf
COPY app app

EXPOSE 9000

CMD ["sbt", "-batch", "run"]
