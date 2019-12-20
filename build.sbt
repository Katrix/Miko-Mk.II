import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

lazy val sharedSettings = Seq(
  organization := "net.katsstuff",
  scalaVersion := "2.13.1",
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

lazy val ackCordVersion    = "0.16.0-SNAPSHOT"
lazy val akkaVersion       = "2.6.1"
lazy val akkaHttpVersion   = "10.1.11"
lazy val doobieVersion     = "0.8.6"
lazy val scalacacheVersion = "0.28.0"

lazy val mikoVersion = "0.1"

lazy val shared =
  crossProject(JSPlatform, JVMPlatform)
    .crossType(CrossType.Pure)
    .settings(
      sharedSettings,
      name := "Miko Shared",
      version := mikoVersion,
      libraryDependencies += "net.katsstuff" %%% "ackcord-data" % ackCordVersion
    )
    .jsSettings(
      libraryDependencies += "org.scala-js" %%% "scalajs-java-time" % "0.2.6"
    )

lazy val sharedJVM = shared.jvm
lazy val sharedJS  = shared.js

lazy val miko = project
  .settings(
    sharedSettings,
    name := "Miko Mk.II",
    version := mikoVersion,
    resolvers += Resolver.JCenterRepository,
    libraryDependencies ++= Seq(
      "net.katsstuff" %% "ackcord-core"            % ackCordVersion,
      "net.katsstuff" %% "ackcord-commands"        % ackCordVersion,
      "net.katsstuff" %% "ackcord-lavaplayer-core" % ackCordVersion
    ),
    libraryDependencies ++= Seq(evolutions, jdbc),
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http-xml"     % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-slf4j"        % akkaVersion,
      "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion
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
    libraryDependencies += "org.bouncycastle" % "bcpg-jdk15on"     % "1.64",
    libraryDependencies += "com.vmunier"      %% "scalajs-scripts" % "1.1.4",
    libraryDependencies += "com.lihaoyi"      %% "scalatags"       % "0.7.0",
    libraryDependencies += "org.webjars.npm"  % "bulma"            % "0.7.5",
    libraryDependencies += "org.webjars.npm"  % "bulma-divider"    % "2.0.1",
    libraryDependencies += "org.webjars.npm"  % "bulma-slider"     % "2.0.0",
    scalaJSProjects := Seq(mikoWeb),
    pipelineStages in Assets := Seq(scalaJSPipeline),
    compile in Compile := ((compile in Compile) dependsOn scalaJSPipeline).value,
    WebKeys.packagePrefix in Assets := "public/",
    managedClasspath in Runtime += (packageBin in Assets).value,
    sources in (Compile, doc) := Seq.empty,
    publishArtifact in (Compile, packageDoc) := false
  )
  .enablePlugins(PlayScala, LauncherJarPlugin)
  .disablePlugins(PlayLayoutPlugin)
  .dependsOn(sharedJVM)

lazy val mikoWeb = project
  .enablePlugins(ScalaJSPlugin, ScalaJSWeb)
  .settings(
    sharedSettings,
    name := "Miko Web Js",
    version := mikoVersion,
    libraryDependencies += "org.scala-js" %%% "scalajs-dom" % "0.9.8",
    skip in packageJSDependencies := false,
    scalaJSUseMainModuleInitializer := true
  )
  .dependsOn(sharedJS)

lazy val miko2Root = project.in(file(".")).aggregate(miko, mikoWeb, sharedJVM, sharedJS)
