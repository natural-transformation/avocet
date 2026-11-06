import xerial.sbt.Sonatype._
import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}

val org = "com.natural-transformation"

val GitHubOwner = "natural-transformation"
val GitHubRepo  = "avocet"
val GitHubEmail = "zli@natural-transformation.com"

def releaseVersion: String = sys.env.getOrElse("RELEASE_VERSION", "")
def isRelease: Boolean     = releaseVersion != ""
val BaseVersion: String    = "1.0.0"
def publishVersion: String = if (isRelease) releaseVersion else s"$BaseVersion-SNAPSHOT"

// Keep build-wide `version` in sync with the version we actually publish.
ThisBuild / version := publishVersion

// OSSRH (Nexus 2) is sunset; publishing happens via Sonatype Central.
//
// - SNAPSHOTs: publish directly to the Central Portal snapshots repository.
// - Releases: use sbt-sonatype's *Central* commands (sonatypeCentralUpload/sonatypeCentralRelease),
//   which talk to the OSSRH Staging API Service (a compatibility layer backed by Central).
//
// Docs:
// - https://central.sonatype.org/pages/ossrh-eol/
// - https://central.sonatype.org/publish/publish-portal-snapshots/
val CentralPortalHost           = "central.sonatype.com"
val CentralPortalSnapshotsRepo  = "https://central.sonatype.com/repository/maven-snapshots/"
val CentralNexusRealm           = "Sonatype Nexus Repository Manager"
val OssrhStagingApiHost         = "ossrh-staging-api.central.sonatype.com"
val OssrhStagingApiServiceLocal = "https://ossrh-staging-api.central.sonatype.com/service/local"
val OssrhStagingApiRealm        = "OSSRH Staging API Service"

// IMPORTANT:
// The release CI runs `sbt sonatypeCentralRelease` from the *root* aggregator project.
// Root does NOT use `publishSettings`, so we must set these keys at the build level.
//
// Otherwise sbt-sonatype uses its default credential host and fails with:
//   "sonatypeCredentialHost key needs to be set to central.sonatype.com ..."
ThisBuild / sonatypeCredentialHost := CentralPortalHost
ThisBuild / sonatypeRepository     := OssrhStagingApiServiceLocal
ThisBuild / sonatypeProfileName    := org
ThisBuild / sonatypeProjectHosting := Some(GitHubHosting(GitHubOwner, GitHubRepo, GitHubEmail))

// Publishing credentials (env vars):
// - SONATYPE_CENTRAL_*: Central Portal snapshots repository (central.sonatype.com)
// - SONATYPE_STAGING_*: OSSRH Staging API Service for releases (ossrh-staging-api.central.sonatype.com)
val CentralUserEnvVar = "SONATYPE_CENTRAL_USERNAME"
val CentralPassEnvVar = "SONATYPE_CENTRAL_PASSWORD"
val StagingUserEnvVar = "SONATYPE_STAGING_USERNAME"
val StagingPassEnvVar = "SONATYPE_STAGING_PASSWORD"

def envUserPass(userKey: String, passKey: String): Option[(String, String)] = {
  val user = sys.env.getOrElse(userKey, "")
  val pass = sys.env.getOrElse(passKey, "")
  if (user.nonEmpty && pass.nonEmpty) Some((user, pass)) else None
}

// IMPORTANT:
// The release CI runs `sbt publishSigned` and `sbt sonatypeCentralRelease` from the *root* aggregator project.
// Root does NOT use `publishSettings`, so we must wire credentials at the build level as well.
//
// This fixes:
//   [MISSING_CREDENTIAL] No credential is found for central.sonatype.com
ThisBuild / credentials ++= {
  val credsFile = Path.userHome / ".sbt" / "sonatype_credentials"
  // We support either:
  // - ~/.sbt/sonatype_credentials (local; user/pass reused for both endpoints)
  // - SONATYPE_CENTRAL_USERNAME / SONATYPE_CENTRAL_PASSWORD (Central Portal snapshots + Central releases)
  // - SONATYPE_STAGING_USERNAME / SONATYPE_STAGING_PASSWORD (release publishing via OSSRH Staging API Service)
  val userPassFromFile: Option[(String, String)] =
    if (credsFile.exists()) {
      val kv =
        IO.readLines(credsFile)
          .iterator
          .map(_.trim)
          .filter(l => l.nonEmpty && !l.startsWith("#"))
          .flatMap { l =>
            l.split("=", 2) match {
              case Array(k, v) => Some((k.trim.toLowerCase, v.trim))
              case _           => None
            }
          }
          .toMap

      val user = kv.get("user").orElse(kv.get("username")).getOrElse("")
      val pass = kv.getOrElse("password", "")

      if (user.nonEmpty && pass.nonEmpty) Some((user, pass)) else None
    } else None

  val centralUserPass: Option[(String, String)] =
    envUserPass(CentralUserEnvVar, CentralPassEnvVar).orElse(userPassFromFile)

  val stagingUserPass: Option[(String, String)] =
    envUserPass(StagingUserEnvVar, StagingPassEnvVar).orElse(userPassFromFile)

  val creds = Seq.newBuilder[Credentials]

  centralUserPass.foreach { case (user, pass) =>
    // Central Portal snapshots (for -SNAPSHOT versions) + Sonatype Central release commands
    creds += Credentials(CentralNexusRealm, CentralPortalHost, user, pass)
  }

  stagingUserPass.foreach { case (user, pass) =>
    // OSSRH Staging API Service (for releases; backed by Central)
    creds += Credentials(OssrhStagingApiRealm, OssrhStagingApiHost, user, pass)
  }

  creds.result()
}

val unusedRepo = Some(Resolver.file("Unused transient repository", file("target/unusedrepo")))

val publishSettings = Seq(
  // Credentials are configured at `ThisBuild` scope above so `sonatypeCentralRelease` works
  // even when invoked from the root aggregator project.
  pomIncludeRepository := { _ => false },
  // For snapshots, publish directly to the Central Portal snapshots repository.
  // For releases, keep using sbt-sonatype's bundle flow (which uses the staging API host below).
  publishTo := {
    if (version.value.endsWith("-SNAPSHOT")) Some("central-portal-snapshots" at CentralPortalSnapshotsRepo)
    else sonatypePublishToBundle.value
  },
  // sbt-sonatype Central commands use the OSSRH Staging API Service (backed by Central).
  // sonatypeCentral* commands require the credential host to be `central.sonatype.com`.
  sonatypeCredentialHost := CentralPortalHost,
  sonatypeRepository     := OssrhStagingApiServiceLocal,
  sonatypeProfileName    := org,
  Test / publishArtifact := false,
  publishMavenStyle := true,
  licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt")),
  headerLicense := Some(HeaderLicense.ALv2("2017-2020", "Aleksey Fomkin")),
  headerSources / excludeFilter := HiddenFileFilter || "IntStringMap.scala" || "StringSet.scala",
  // This fork is published from https://github.com/natural-transformation/avocet
  sonatypeProjectHosting := Some(GitHubHosting(GitHubOwner, GitHubRepo, GitHubEmail))
)

val dontPublishSettings = Seq(
  publish := {},
  publishTo := unusedRepo,
  publishArtifact := false
)

def additionalUnmanagedSources(cfg: Configuration) = Def.setting {
  val baseDir = (cfg / baseDirectory).value
  val crossType = baseDir.getName match {
    case ".js"     => CrossType.Pure
    case ".jvm"    => CrossType.Pure
    case ".native" => CrossType.Pure
    case "js"     => CrossType.Full
    case "jvm"    => CrossType.Full
    case "native" => CrossType.Full
    case _ => CrossType.Dummy
  }
  val versionSpecificDirNames = CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((3, _))  => Seq("scala-3")
    case _             => Seq()
  }
  val origSourceDir = (cfg / sourceDirectory).value

  val result = crossType match {
    case CrossType.Pure =>
      val sourceDir = origSourceDir.getName
      val f = file(baseDir.getParent) / "src" / origSourceDir.getName
      versionSpecificDirNames.map(f / _)
    case CrossType.Full =>
      val f = file(baseDir.getParent) / "shared" / "src" / origSourceDir.getName
      versionSpecificDirNames.map(origSourceDir / _) ++ versionSpecificDirNames.map(f / _)
    case CrossType.Dummy =>
      versionSpecificDirNames.map(origSourceDir / _)
  }
  result
}

val crossVersionSettings = Seq(
  crossScalaVersions := Seq("3.3.7"),
  scalaVersion := "3.3.7"
)

val commonSettings = Seq(
  scalaVersion := "3.3.7",
  organization := org,
  version      := publishVersion,
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked"
  ),
  testFrameworks += new TestFramework("utest.runner.Framework"),
  libraryDependencies ++= Seq(
    "com.lihaoyi" %%% "utest" % "0.7.10" % "test",
    "org.scalacheck" %%% "scalacheck" % "1.15.4" % "test"
  ),
  // Add some more source directories
  Compile / unmanagedSourceDirectories ++= additionalUnmanagedSources(Compile).value,
  Test/ unmanagedSourceDirectories ++= additionalUnmanagedSources(Test).value
)


lazy val core = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Full)
  .enablePlugins(GitVersioning)
  .enablePlugins(HeaderPlugin)
  .settings(commonSettings: _*)
  .settings(crossVersionSettings:_*)
  .settings(publishSettings: _*)
  .settings(
    normalizedName := "avocet-core"
  )

lazy val coreJS = core.js
lazy val coreJVM = core.jvm

lazy val events = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .enablePlugins(GitVersioning)
  .enablePlugins(HeaderPlugin)
  .settings(commonSettings: _*)
  .settings(crossVersionSettings:_*)
  .settings(publishSettings: _*)
  .settings(normalizedName := "avocet-events")
  .dependsOn(core)

lazy val eventsJS = events.js
lazy val eventsJVM = events.jvm

lazy val dom = project
  .enablePlugins(ScalaJSPlugin)
  .enablePlugins(GitVersioning)
  .enablePlugins(HeaderPlugin)
  .settings(commonSettings: _*)
  .settings(crossVersionSettings:_*)
  .settings(publishSettings: _*)
  .dependsOn(coreJS)
  .dependsOn(eventsJS)
  .settings(
    normalizedName := "avocet-dom",
    //scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      ("org.scala-js" %%% "scalajs-dom" % "1.1.0").cross(CrossVersion.for3Use2_13)
    )
  )

lazy val domFixture = project
  .in(file("dom-fixture"))
  .enablePlugins(ScalaJSPlugin)
  .settings(commonSettings: _*)
  .settings(crossVersionSettings:_*)
  .settings(dontPublishSettings: _*)
  .dependsOn(dom)
  .settings(
    normalizedName := "avocet-dom-fixture",
    // Browser fixture for the DOM adapter; see misc/dom-fixture/index.html.
    scalaJSUseMainModuleInitializer := true,
    libraryDependencies ++= Seq(
      ("org.scala-js" %%% "scalajs-dom" % "1.1.0").cross(CrossVersion.for3Use2_13)
    )
  )

lazy val bench = project
  .enablePlugins(JmhPlugin)
  .enablePlugins(SbtTwirl)
  .settings(commonSettings: _*)
  .settings(dontPublishSettings: _*)
  .dependsOn(coreJVM)
  .settings(
    normalizedName := "avocet-bench",
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "scalatags" % "0.13.1"
    )
  )

lazy val root = project
  .in(file("."))
  .settings(commonSettings:_*)
  .settings(crossVersionSettings:_*)
  .settings(dontPublishSettings:_*  )
  .settings(normalizedName := "avocet")
  .aggregate(
    coreJS, coreJVM,
    eventsJS, eventsJVM,
    dom, domFixture
  )

// Don't use it for root project
// For some unknown reason `headerLicense := None` doesn't work.
disablePlugins(HeaderPlugin)
