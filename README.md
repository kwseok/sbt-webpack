# sbt-webpack
An sbt-web plugin for the webpack module bundler.

Add plugin
----------

Add the plugin to `project/plugins.sbt`.

```scala
addSbtPlugin("com.github.stonexx.sbt" % "sbt-webpack" % "1.0.6")
```

Your project's build file also needs to enable sbt-web plugins. For example with build.sbt:

    lazy val root = (project.in file(".")).enablePlugins(SbtWeb)

From the sbt console:

* Run webpack with `webpack:run`
* Start watch mode with `webpack:startWatch`
* Stop watch mode with `webpack:stopWatch`

Add webpack as a devDependancy to your package.json file (located at the root of your project):
```json
{
  "devDependencies": {
    "webpack": "^1.14.0"
  }
}
```

Configuration
-------------

```scala
WebpackKeys.config := [location of config file]
```
(if not set, defaults to baseDirectory / "webpack.config.js")

You need to set the source filters to include the same resources that are matched by the module loaders. For example:
```scala
includeFilter in Webpack := "*.js"
```
See [how to include/exclude files in the source directory](http://www.scala-sbt.org/1.0/docs/Howto-Customizing-Paths.html#Include%2Fexclude+files+in+the+source+directory) for how to configure this setting.

Make sure that this stays in sync with the webpack configuration otherwise sbt will not invalidate the cache and resources will not be regenerated correctly.

## License
`sbt-webpack` is licensed under the [Apache License, Version 2.0](https://github.com/stonexx/sbt-webpack/blob/master/LICENSE)
