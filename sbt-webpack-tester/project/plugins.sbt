lazy val root = Project("plugins", file(".")).dependsOn(plugin)

lazy val plugin = file("../").getCanonicalFile.toURI

resolvers ++= DefaultOptions.resolvers(snapshot = true) ++ Seq(
  Resolver.mavenLocal,
  Resolver.sbtPluginRepo("snapshots")
)
