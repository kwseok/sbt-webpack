lazy val root = Project("plugins", file(".")).dependsOn(plugin)

lazy val plugin = file("../").getCanonicalFile.toURI

resolvers += Resolver.mavenLocal

libraryDependencies += "org.slf4j" % "slf4j-nop" % "1.7.25"
