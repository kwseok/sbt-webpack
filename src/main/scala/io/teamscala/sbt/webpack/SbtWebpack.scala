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
    val webpack = TaskKey[Seq[File]]("webpack", "Run the webpack module bundler.")

    object WebpackKeys {
      val config = SettingKey[File]("webpackConfig", "The location of a webpack configuration file.")
    }
  }

  import autoImport._
  import autoImport.WebpackKeys._

  override def projectSettings: Seq[Setting[_]] = Seq(
    resourceManaged in webpack := webTarget.value / webpack.key.label,
    resourceGenerators in Assets <+= webpack,
    managedResourceDirectories in Assets += (resourceManaged in webpack).value,
    webpack <<= runWebpack dependsOn (nodeModules in Plugin, nodeModules in Assets, webModules in Assets),
    config := baseDirectory.value / "webpack.config.js"
  )

  case object WebpackFailure extends NoStackTrace

  private def relativizedPath(base: File, file: File): String =
    relativeTo(base)(file).getOrElse(file.absolutePath)

  private def runWebpack: Def.Initialize[Task[Seq[File]]] = Def.task {
    val context = (sourceDirectory in webpack).value
    val outputPath = (resourceManaged in webpack).value

    val runUpdate = FileFunction.cached(streams.value.cacheDirectory / webpack.key.label, FilesInfo.hash) { _ =>
      streams.value.log.info(s"Webpack running by ${relativizedPath(baseDirectory.value, config.value)}")

      val nodeModulePaths = (nodeModuleDirectories in Plugin).value.map(_.getCanonicalPath)

      val webpackExecutable = SbtWeb.copyResourceTo(
        (target in Plugin).value / webpack.key.label,
        getClass.getClassLoader.getResource("webpack.js"),
        streams.value.cacheDirectory / "copy-resource"
      )

      val args = Seq(config.value.absolutePath, JsObject(
        "context" -> JsString(context.absolutePath),
        "output" -> JsObject(
          "path" -> JsString(outputPath.absolutePath)
        )
      ).toString())

      val execution = try SbtJsTask.executeJs(
        state.value,
        (engineType in webpack).value,
        (command in webpack).value,
        nodeModulePaths,
        webpackExecutable,
        args,
        (timeoutPerSource in webpack).value
      ) catch {
        case _: JsTaskFailure => throw WebpackFailure
      }
      execution match {
        case Seq(JsBoolean(true))  => outputPath.***.get.toSet
        case Seq(JsBoolean(false)) => throw WebpackFailure
      }
    }

    val include = (includeFilter in webpack).value
    val exclude = (excludeFilter in webpack).value
    val inputFiles = (context ** (include && -exclude)).get.filterNot(_.isDirectory)
    runUpdate((config.value +: inputFiles).toSet).filter(_.isFile).toSeq
  }
}
