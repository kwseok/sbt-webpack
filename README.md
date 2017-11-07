# sbt-webpack [![Build Status](https://travis-ci.org/stonexx/sbt-webpack.svg?branch=master)](https://travis-ci.org/stonexx/sbt-webpack) 
An sbt-web plugin for the webpack module bundler.

Add plugin
----------

Add the plugin to `project/plugins.sbt`.

```scala
addSbtPlugin("com.github.stonexx.sbt" % "sbt-webpack" % "1.2.0")
```

Your project's build file also needs to enable sbt-web plugins. For example with build.sbt:

    lazy val root = (project.in file(".")).enablePlugins(SbtWeb)

If you want to run the webpack before another task. For example with build.sbt:

    WebKeys.pipeline := WebKeys.pipeline.dependsOn(webpack.toTask("")).value

From the sbt console:

* Run webpack production mode with `webpack` or `webpack prod`
* Run webpack development mode with `webpack dev`
* Run webpack testing mode with `webpack test`
* Start watch mode with `webpack watch`
* Stop watch mode with `webpack stop`

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
WebpackKeys.config in webpack := [location of config file]
WebpackKeys.config in (WebpackModes.Dev, webpack) := [location of config file for development mode]
WebpackKeys.config in (WebpackModes.Prod, webpack) := [location of config file for production mode]
WebpackKeys.config in (WebpackModes.Test, webpack) := [location of config file for testing mode]
```
(if not set, defaults to baseDirectory / "webpack.config.js")

You need to set the source filters to include the same resources that are matched by the module loaders. For example:
```scala
includeFilter in webpack := "*.js"
includeFilter in (WebpackModes.Dev, webpack) := "*.js" // for development mode
includeFilter in (WebpackModes.Prod, webpack) := "*.js" // for production mode
includeFilter in (WebpackModes.Test, webpack) := "*.js" // for testing mode
```
See [how to include/exclude files in the source directory](http://www.scala-sbt.org/1.0/docs/Howto-Customizing-Paths.html#Include%2Fexclude+files+in+the+source+directory) for how to configure this setting.

Make sure that this stays in sync with the webpack configuration otherwise sbt will not invalidate the cache and resources will not be regenerated correctly.

## License
`sbt-webpack` is licensed under the [Apache License, Version 2.0](https://github.com/stonexx/sbt-webpack/blob/master/LICENSE)
