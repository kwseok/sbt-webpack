resolvers += Resolver.typesafeRepo("maven-releases")

libraryDependencies += "org.slf4j" % "slf4j-nop" % "1.7.25"

addSbtPlugin("com.github.stonexx.sbt" % "sbt-webpack" % sys.props("project.version"))
