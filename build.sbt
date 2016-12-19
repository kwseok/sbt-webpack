sbtPlugin := true

organization := "com.github.stonexx.sbt"

name := "sbt-webpack"

scalaVersion := "2.10.6"

resolvers ++= Seq(
  Resolver.typesafeRepo("releases"),
  Resolver.jcenterRepo
)

libraryDependencies += "org.webjars" % "npm" % "4.0.2"
libraryDependencies += "org.webjars.npm" % "lodash" % "4.17.2"

addSbtPlugin("com.typesafe.sbt" %% "sbt-js-engine" % "1.1.4")

licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0.html"))
