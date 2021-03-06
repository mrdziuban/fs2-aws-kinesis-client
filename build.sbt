lazy val scala213 = "2.13.8"
lazy val fs2Version = "3.2.4"
lazy val scalaTestVersion = "3.2.10"
lazy val mockitoScalaTestVersion = "1.17.0"
lazy val mockitoCoreVersion = "4.3.1"
lazy val catsEffectVersion = "3.3.0"

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / scalaVersion := scala213

lazy val baseSettings = Seq(
  organization := "io.laserdisc",
  developers := List(
    Developer(
      "mrdziuban",
      "Matt Dziuban",
      "mrdziuban@gmail.com",
      url("https://github.com/mrdziuban")
    )
  ),
  licenses ++= Seq(("MIT", url("http://opensource.org/licenses/MIT"))),
  homepage := Some(url("https://github.com/mrdziuban/fs2-aws-kinesis-client")),
  scalaVersion := scala213,
  Test / fork := true,
  scalacOptions -= "-Vtype-diffs",
  scalacOptions ++= Seq(
    "-Vimplicits",
    "-Vimplicits-verbose-tree",
  ),
  addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1"),
  addCompilerPlugin("org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full),
)

lazy val `fs2-aws-kinesis-client` = project.in(file("."))
  .settings(baseSettings)
  .settings(
    name := "fs2-aws-kinesis-client",
    libraryDependencies ++= Seq(
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version,
      "software.amazon.kinesis" % "amazon-kinesis-client" % "2.3.10",
      "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
      "org.mockito" %% "mockito-scala-scalatest" % mockitoScalaTestVersion % Test,
      "org.mockito" % "mockito-core" % mockitoCoreVersion % Test,
      "ch.qos.logback" % "logback-classic" % "1.2.10" % Test,
      "ch.qos.logback" % "logback-core" % "1.2.10" % Test
    ),
  )

lazy val `fs2-aws-kinesis-examples` = project.in(file("examples"))
  .settings(baseSettings)
  .settings(
    name := "fs2-aws-kinesis-examples"
  )
  .dependsOn(`fs2-aws-kinesis-client`)
  .aggregate(`fs2-aws-kinesis-client`)
