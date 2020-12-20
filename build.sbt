ThisBuild / turbo := true

lazy val sharedSettings = Seq(
  organization := "net.katsstuff",
  scalaVersion := "2.13.2",
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
  addCompilerPlugin("org.typelevel" %% "kind-projector" % "0.11.0" cross CrossVersion.full)
)

lazy val ackCordVersion    = "0.18.0-SNAPSHOT"
lazy val akkaVersion       = "2.6.6"
lazy val akkaHttpVersion   = "10.1.11"
lazy val doobieVersion     = "0.9.0"
lazy val scalacacheVersion = "0.28.0"

lazy val mikoVersion = "0.1"

lazy val miko = project
  .in(file("."))
  .settings(
    sharedSettings,
    name := "Miko Mk.II",
    version := mikoVersion,
    resolvers += Resolver.JCenterRepository,
    libraryDependencies ++= Seq(
      "net.katsstuff" %% "ackcord-core"            % ackCordVersion,
      "net.katsstuff" %% "ackcord-commands"        % ackCordVersion,
      "net.katsstuff" %% "ackcord-commands"        % ackCordVersion,
      "net.katsstuff" %% "ackcord-slash-commands"  % ackCordVersion,
      "net.katsstuff" %% "ackcord-lavaplayer-core" % ackCordVersion,
      "com.sedmelluq" % "lavaplayer"               % "1.3.65"
    ),
    libraryDependencies ++= Seq(evolutions, jdbc),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http-xml"     % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-slf4j"        % akkaVersion,
      "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion
    ),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio"              % "1.0.0",
      "dev.zio" %% "zio-interop-cats" % "2.1.4.0"
    ),
    libraryDependencies ++= Seq(
      "org.tpolecat" %% "doobie-core"     % doobieVersion,
      "org.tpolecat" %% "doobie-hikari"   % doobieVersion,
      "org.tpolecat" %% "doobie-postgres" % doobieVersion
    ),
    libraryDependencies ++= Seq(
      "com.github.cb372" %% "scalacache-caffeine"    % scalacacheVersion,
      "com.github.cb372" %% "scalacache-cats-effect" % scalacacheVersion
    ),
    libraryDependencies += "org.bouncycastle" % "bcpg-jdk15on"     % "1.65",
    libraryDependencies += "com.vmunier"      %% "scalajs-scripts" % "1.1.4",
    libraryDependencies += "com.lihaoyi"      %% "scalatags"       % "0.9.0",
    libraryDependencies += "com.lihaoyi"      %% "pprint"          % "0.5.9",
    libraryDependencies += "org.scala-lang"   % "scala-compiler"   % scalaVersion.value,
    WebKeys.packagePrefix in Assets := "public/",
    managedClasspath in Runtime += (packageBin in Assets).value,
    sources in (Compile, doc) := Seq.empty,
    publishArtifact in (Compile, packageDoc) := false,
    //Webpack stuff
    Assets / webpackDevConfig := baseDirectory.value / "webpack.config.dev.js",
    Assets / webpackProdConfig := baseDirectory.value / "webpack.config.prod.js",
    //webpackMonitoredDirectories in Assets += baseDirectory.value / "src" / "main" / "assets",
    includeFilter in webpackMonitoredFiles in Assets := "*.vue" || "*.js",
    webpackMonitoredFiles in Assets ++= Seq(
      baseDirectory.value / "webpack.config.common.js",
      baseDirectory.value / ".postcssrc.js",
      baseDirectory.value / ".browserlistrc"
    ),
    pipelineStages := Seq(digest, gzip)
  )
  .enablePlugins(PlayScala, LauncherJarPlugin, WebpackPlugin)
  .disablePlugins(PlayLayoutPlugin)
