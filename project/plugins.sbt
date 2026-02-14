addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "3.11.0")

addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.2.1")

addSbtPlugin("com.github.sbt" % "sbt-git" % "2.0.1")

// `crossProject(...)` is provided by sbt-crossproject.
// `sbt-scalajs-crossproject` depends on it, but we keep it explicit so sbtix can lock it for Nix builds.
addSbtPlugin("org.portable-scala" % "sbt-crossproject" % "1.3.2")

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.20.2")

addSbtPlugin("pl.project13.scala" % "sbt-jmh" % "0.4.7")

addSbtPlugin("org.playframework.twirl" % "sbt-twirl" % "2.0.9")

addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.0.0")
