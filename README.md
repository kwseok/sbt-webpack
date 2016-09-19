# sbt-webpack
An sbt-web plugin for the webpack module bundler.

Add plugin
----------

Add the plugin to `project/plugins.sbt`.

```scala
addSbtPlugin("com.github.stonexx.sbt" % "sbt-webpack" % "1.0.4")
```

Your project's build file also needs to enable sbt-web plugins. For example with build.sbt:

    lazy val root = (project.in file(".")).enablePlugins(SbtWeb)

Add webpack as a devDependancy to your package.json file (located at the root of your project):
```json
{
  "devDependencies": {
    "webpack": "^1.13.1"
  }
}
```

Configuration
-------------

```scala
WebpackKeys.config := [location of config file]
```
(if not set, defaults to baseDirectory / "webpack.config.js")

## License
`sbt-webpack` is licensed under the [Apache License, Version 2.0](https://github.com/stonexx/sbt-webpack/blob/master/LICENSE)
