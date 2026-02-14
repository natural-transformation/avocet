addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.11.0")

addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.2.1")

addSbtPlugin("com.github.sbt" % "sbt-git" % "2.0.1")

// `crossProject(...)` is provided by sbt-crossproject.
// `sbt-scalajs-crossproject` depends on it, but we keep it explicit so sbtix can lock it for Nix builds.
addSbtPlugin("org.portable-scala" % "sbt-crossproject" % "1.3.2")

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.20.2")

addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.3.3")

addSbtPlugin("com.typesafe.sbt" % "sbt-twirl" % "1.5.1")

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.0.0")

// sbt 1.10.x runs on Scala 2.12.20 which depends on scala-xml 2.3.0.
// Older plugins (like sbt-twirl 1.5.x) pull scala-xml 1.2.0, which causes an
// eviction error during project load. Force scala-xml to a single version.
dependencyOverrides += "org.scala-lang.modules" %% "scala-xml" % "2.3.0"
