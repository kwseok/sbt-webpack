package io.teamscala.sbt.webpack

import com.typesafe.sbt.jse._
import com.typesafe.sbt.jse.JsEngineImport.JsEngineKeys._
import com.typesafe.sbt.jse.JsTaskImport.JsTaskKeys._
import com.typesafe.sbt.jse.SbtJsTask.JsTaskFailure
import com.typesafe.sbt.web.SbtWeb
import com.typesafe.sbt.web.SbtWeb.autoImport._
import com.typesafe.sbt.web.SbtWeb.autoImport.WebKeys._
import sbt._
import sbt.Keys._
import spray.json.{JsString, JsBoolean, JsObject}

import scala.util.control.NoStackTrace

object SbtWebpack extends AutoPlugin {
  override def requires = SbtJsTask

  override def trigger = AllRequirements

  object autoImport {
    object WebpackKeys {
      val webpack       = TaskKey[Seq[File]]("webpack", "Run the webpack module bundler.")
      val webpackConfig = SettingKey[File]("webpackConfig", "The location of a webpack configuration file.")
    }
  }

  import autoImport.WebpackKeys._

  override def projectSettings: Seq[Setting[_]] = Seq(Assets, TestAssets).flatMap { config =>
    Seq(
      webpackConfig in config := baseDirectory.value / "webpack.config.js",
      resourceGenerators in config <+= webpack in config,
      resourceManaged in webpack in config := webTarget.value / webpack.key.label / (if (config == Assets) "main" else "test"),
      resourceDirectories in config += (resourceManaged in webpack in config).value,
      webpack in config := runWebpack(config).dependsOn(webJarsNodeModules in Plugin).dependsOn(npmNodeModules in config).value
    )
  }

  case object WebpackFailure extends NoStackTrace with UnprintableException

  private def runWebpack(config: Configuration): Def.Initialize[Task[Seq[File]]] = Def.task {
    val configFile = (webpackConfig in config).value

    val include = (includeFilter in webpack in config).value
    val exclude = (excludeFilter in webpack in config).value
    val sourceDir = (sourceDirectory in webpack in config).value
    val inputFiles = (sourceDir ** (include && -exclude)).get.filterNot(_.isDirectory)

    val cachedFn = FileFunction.cached(streams.value.cacheDirectory / "run", FilesInfo.lastModified, FilesInfo.exists) { _ =>
      val relativizedConfigPath = IO.relativize(baseDirectory.value, configFile).getOrElse(configFile.absolutePath)
      streams.value.log.info(s"Webpack building by $relativizedConfigPath")

      val outputDir = (resourceManaged in webpack in config).value

      val nodeModulePaths = (nodeModuleDirectories in Plugin).value.map(_.getPath)

      val webpackExecutable = SbtWeb.copyResourceTo(
        (target in Plugin).value / webpack.key.label,
        getClass.getClassLoader.getResource("webpack.js"),
        streams.value.cacheDirectory / "copy-resource"
      )

      val args = Seq(configFile.absolutePath, JsObject(
        "context" -> JsString(sourceDir.absolutePath),
        "output" -> JsObject(
          "path" -> JsString(outputDir.absolutePath)
        )
      ).toString())

      val execution = try {
        SbtJsTask.executeJs(
          state.value,
          (engineType in webpack).value,
          (command in webpack).value,
          nodeModulePaths,
          webpackExecutable,
          args,
          (timeoutPerSource in webpack).value
        )
      } catch {
        case _: JsTaskFailure => throw WebpackFailure
      }
      execution match {
        case Seq(JsBoolean(true))  => //Success
        case Seq(JsBoolean(false)) => throw WebpackFailure
      }

      outputDir.***.get.toSet
    }
    cachedFn((configFile +: inputFiles).toSet).toSeq
  }
}
