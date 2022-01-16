import scala.concurrent.duration._

ThisBuild / turbo := true
ThisBuild / forceUpdatePeriod := Some(1.minutes)
ThisBuild / csrConfiguration := csrConfiguration.value.withTtl(1.minute)

lazy val sharedSettings = Seq(
  organization := "net.katsstuff",
  scalaVersion := "2.13.7",
  scalacOptions ++= Seq(
    "-deprecation",
    "-encoding",
    "utf-8",
    "-explaintypes",
    "-feature",
    "-unchecked",
    "-Xcheckinit",
    "-Xlint",
    "-Wdead-code",
    "-Wunused"
  ),
  resolvers += Opts.resolver.sonatypeSnapshots,
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.13.2" cross CrossVersion.full)
)

lazy val ackCordVersion    = "0.18.0-SNAPSHOT"
lazy val akkaVersion       = "2.6.18"
lazy val akkaHttpVersion   = "10.2.7"
lazy val doobieVersion     = "1.0.0-RC1"
lazy val scalacacheVersion = "1.0.0-M6"

lazy val mikoVersion = "0.1"

lazy val miko = project
  .in(file("."))
  .settings(
    sharedSettings,
    name := "Miko Mk.II",
    version := mikoVersion,
    resolvers += "dv8tion" at "https://m2.dv8tion.net/releases",
    csrConfiguration := csrConfiguration.value.withTtl(10.minute),
    libraryDependencies ++= Seq(
      "net.katsstuff" %% "ackcord-core"            % ackCordVersion,
      "net.katsstuff" %% "ackcord-commands"        % ackCordVersion,
      "net.katsstuff" %% "ackcord-commands"        % ackCordVersion,
      "net.katsstuff" %% "ackcord-interactions"    % ackCordVersion,
      "net.katsstuff" %% "ackcord-lavaplayer-core" % ackCordVersion,
      "com.sedmelluq" % "lavaplayer"               % "1.3.78"
    ),
    libraryDependencies ++= Seq(evolutions, jdbc),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http-xml"              % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-slf4j"                 % akkaVersion,
      "com.typesafe.akka" %% "akka-stream-typed"          % akkaVersion,
      "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion
    ),
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "doobie-core"     % doobieVersion,
      "org.tpolecat" %% "doobie-hikari"   % doobieVersion,
      "org.tpolecat" %% "doobie-postgres" % doobieVersion
    ),
    libraryDependencies ++= Seq(
      "com.github.cb372" %% "scalacache-caffeine" % scalacacheVersion
    ),
    libraryDependencies += "org.bouncycastle"          % "bcpg-jdk15on"     % "1.69",
    libraryDependencies += "com.vmunier"               %% "scalajs-scripts" % "1.2.0",
    libraryDependencies += "com.lihaoyi"               %% "scalatags"       % "0.9.4",
    libraryDependencies += "com.lihaoyi"               %% "pprint"          % "0.6.6",
    libraryDependencies += "org.scala-lang"            % "scala-compiler"   % scalaVersion.value,
    libraryDependencies += "ch.qos.logback"            % "logback-classic"  % "1.2.10",
    libraryDependencies += "io.github.java-diff-utils" % "java-diff-utils"  % "4.9",
    dependencyOverrides += "org.slf4j"                 % "slf4j-api"        % "1.7.32",
    Assets / WebKeys.packagePrefix := "public/",
    Runtime / managedClasspath += (Assets / packageBin).value,
    Compile / doc / sources := Seq.empty,
    Compile / packageDoc / publishArtifact := false,
    //Webpack stuff
    Assets / webpackDevConfig := baseDirectory.value / "webpack.config.dev.js",
    Assets / webpackProdConfig := baseDirectory.value / "webpack.config.prod.js",
    //webpackMonitoredDirectories in Assets += baseDirectory.value / "src" / "main" / "assets",
    Assets / webpackMonitoredFiles / includeFilter := "*.vue" || "*.js",
    Assets / webpackMonitoredFiles ++= Seq(
      baseDirectory.value / "webpack.config.common.js",
      baseDirectory.value / ".postcssrc.js",
      baseDirectory.value / ".browserlistrc"
    ),
    pipelineStages := Seq(digest, gzip)
  )
  .enablePlugins(PlayScala, LauncherJarPlugin, WebpackPlugin)
  .disablePlugins(PlayLayoutPlugin)
