ThisBuild / scalaVersion := "3.3.8"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "com.pictionary"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

libraryDependencies ++= Seq(
  guice,
  "org.postgresql" % "postgresql" % "42.7.4",
  "org.scalameta" %% "munit" % "1.0.2" % Test
)

Compile / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked"
)

PlayKeys.devSettings := Seq(
  "play.server.http.port" -> "9000"
)
