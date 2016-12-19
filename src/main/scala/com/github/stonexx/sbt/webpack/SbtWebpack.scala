package com.github.stonexx.sbt.webpack

import java.io.IOException
import java.net.URLEncoder

import com.typesafe.jse.LocalEngine
import com.typesafe.sbt.jse.SbtJsTask
import com.typesafe.sbt.web.SbtWeb
import com.typesafe.sbt.web.SbtWeb.autoImport.{WebKeys, _}
import org.apache.commons.compress.utils.CharsetNames.UTF_8
import org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS
import sbt.Defaults.doClean
import sbt._
import sbt.Keys._
import spray.json.{JsonParser, DefaultJsonProtocol, JsObject, JsValue, JsBoolean}

import scala.collection.{mutable, immutable}
import scala.language.implicitConversions

object SbtWebpack extends AutoPlugin {
  override def requires = SbtJsTask

  override def trigger = AllRequirements

  object autoImport {
    val Webpack: Configuration = config("webpack")

    object WebpackModes {
      val Dev : Configuration = config("webpack-dev").extend(Webpack)
      val Prod: Configuration = config("webpack-prod").extend(Webpack)
      val Test: Configuration = config("webpack-test").extend(Webpack)
    }

    object WebpackKeys {
      val config       : SettingKey[File]                   = SettingKey[File]("webpackConfig", "The location of a webpack configuration file.")
      val envVars      : SettingKey[Map[String, String]]    = SettingKey[Map[String, String]]("webpackEnvVars", "Environment variable names and values to set for webpack.")
      val startWatch   : TaskKey[Unit]                      = TaskKey[Unit]("startWatch", "Start the webpack module bundler for watch mode.")
      val stopWatch    : TaskKey[Unit]                      = TaskKey[Unit]("stopWatch", "Stop the webpack module bundler for watch mode.")
      val watcherRunner: AttributeKey[WebpackWatcherRunner] = AttributeKey[WebpackWatcherRunner]("webpackWatcherRunner")
    }

    trait WebpackWatcher {
      def start(): Unit
      def stop(): Unit
    }

    class WebpackWatcherRunner {
      private var currentSubject: Option[WebpackWatcher] = None

      def start(subject: WebpackWatcher): Unit = {
        stop()
        subject.start()
        currentSubject = Some(subject)
      }

      def stop(): Unit = {
        currentSubject.foreach(_.stop())
        currentSubject = None
      }
    }

    implicit def stateToWebpackWatcherRunner(s: State): WebpackWatcherRunner = s.get(WebpackKeys.watcherRunner).get
  }

  import autoImport._
  import autoImport.WebpackKeys._
  import autoImport.WebpackModes._

  private val webpackDependTasks: Seq[Scoped.AnyInitTask] = Seq(
    WebKeys.nodeModules in Plugin,
    WebKeys.nodeModules in Assets,
    WebKeys.webModules in Assets
  )

  override def projectSettings: Seq[Setting[_]] = Seq(
    includeFilter in Webpack := AllPassFilter,
    includeFilter in Dev := (includeFilter in Webpack).value,
    includeFilter in Prod := (includeFilter in Webpack).value,
    includeFilter in Test := (includeFilter in Webpack).value,

    excludeFilter in Webpack := HiddenFileFilter,
    excludeFilter in Dev := (excludeFilter in Webpack).value,
    excludeFilter in Prod := (excludeFilter in Webpack).value,
    excludeFilter in Test := (excludeFilter in Webpack).value,

    config in Webpack := baseDirectory.value / "webpack.config.js",
    config in Dev := (config in Webpack).value,
    config in Prod := (config in Webpack).value,
    config in Test := (config in Webpack).value,

    envVars in Webpack := LocalEngine.nodePathEnv((WebKeys.nodeModuleDirectories in Plugin).value.map(_.getCanonicalPath).to[immutable.Seq]),
    envVars in Dev := (envVars in Webpack).value + ("NODE_ENV" -> "development"),
    envVars in Prod := (envVars in Webpack).value + ("NODE_ENV" -> "production"),
    envVars in Test := (envVars in Webpack).value + ("NODE_ENV" -> "testing"),

    run in Webpack := (run in Prod).evaluated,
    run in Dev := runWebpack(Dev).dependsOn(webpackDependTasks: _*).value,
    run in Prod := runWebpack(Prod).dependsOn(webpackDependTasks: _*).value,
    run in Test := runWebpack(Test).dependsOn(webpackDependTasks: _*).value,

    startWatch in Webpack := state.value.start(new WebpackWatcher {
      private[this] var process: Option[Process] = None

      def start(): Unit = {
        state.value.log.info(s"Starting webpack watcher by ${relativizedPath(baseDirectory.value, (config in Dev).value)}")

        IO.delete(Seq(
          (streams in (Dev, run)).value.cacheDirectory / run.key.label,
          (streams in (Prod, run)).value.cacheDirectory / run.key.label,
          (streams in (Test, run)).value.cacheDirectory / run.key.label
        ))

        process = Some(forkNode(
          baseDirectory.value,
          getWebpackScript.value,
          List(
            (config in Dev).value.absolutePath,
            URLEncoder.encode(JsObject("watch" -> JsBoolean(true)).toString, UTF_8)
          ),
          (envVars in Dev).value,
          state.value.log
        ))
      }

      def stop(): Unit = {
        state.value.log.info(s"Stopping webpack watcher by ${relativizedPath(baseDirectory.value, (config in Dev).value)}")

        process.foreach(_.destroy())
        process = None
      }
    }),
    startWatch in Webpack := (startWatch in Webpack).dependsOn(webpackDependTasks: _*).value,
    stopWatch in Webpack := state.value.stop(),
    onLoad in Global := (onLoad in Global).value.andThen { state =>
      state.put(watcherRunner, new WebpackWatcherRunner)
    },
    onUnload in Global := (onUnload in Global).value.andThen { state =>
      state.stop()
      state.remove(watcherRunner)
    }
  )

  case class NodeMissingException(cause: Throwable) extends RuntimeException("'node' is required. Please install it and add it to your PATH.", cause)
  case class NodeExecuteFailureException(exitValue: Int) extends RuntimeException("Failed to execute node.")

  private def getWebpackScript: Def.Initialize[Task[File]] = Def.task {
    SbtWeb.copyResourceTo(
      (target in Plugin).value / Webpack.name,
      getClass.getClassLoader.getResource("webpack.js"),
      (streams in Webpack).value.cacheDirectory / "copy-resource" / "webpack"
    )
  }

  private def resolveContexts(mode: Configuration): Def.Initialize[Task[Seq[File]]] = Def.task {
    val resolveContextsScript = SbtWeb.copyResourceTo(
      (target in Plugin).value / Webpack.name,
      getClass.getClassLoader.getResource("resolve-contexts.js"),
      (streams in Webpack).value.cacheDirectory / "copy-resource" / "resolve-contexts"
    )
    val results = runNode(
      baseDirectory.value,
      resolveContextsScript,
      List((config in mode).value.absolutePath),
      (envVars in mode).value,
      state.value.log
    )
    import DefaultJsonProtocol._
    results.headOption.toList.flatMap(_.convertTo[Seq[String]]).map(file)
  }

  private def relativizedPath(base: File, file: File): String =
    relativeTo(base)(file).getOrElse(file.absolutePath)

  private def cached(cacheBaseDirectory: File, inStyle: FilesInfo.Style)(action: Set[File] => Unit): Set[File] => Unit = {
    import Path._
    lazy val inCache = Difference.inputs(cacheBaseDirectory / "in-cache", inStyle)
    inputs => {
      inCache(inputs) { inReport =>
        if (inReport.modified.nonEmpty) action(inReport.modified)
      }
    }
  }

  private def runWebpack(mode: Configuration): Def.Initialize[Task[Unit]] = Def.task {
    state.value.get(watcherRunner).foreach(_.stop())

    if (mode != Dev) IO.delete((streams in Dev).value.cacheDirectory / run.key.label)
    if (mode != Prod) IO.delete((streams in Prod).value.cacheDirectory / run.key.label)
    if (mode != Test) IO.delete((streams in Test).value.cacheDirectory / run.key.label)

    val cacheDir = (streams in mode).value.cacheDirectory / run.key.label
    val runUpdate = cached(cacheDir, FilesInfo.hash) { _ =>
      state.value.log.info(s"Running $mode by ${relativizedPath(baseDirectory.value, (config in mode).value)}")

      runNode(
        baseDirectory.value,
        getWebpackScript.value,
        List(
          (config in mode).value.absolutePath,
          URLEncoder.encode(JsObject("watch" -> JsBoolean(false)).toString, UTF_8)
        ),
        (envVars in mode).value,
        state.value.log
      )

      doClean(cacheDir.getParentFile.*(DirectoryFilter).get, Seq(cacheDir))
    }

    val include = (includeFilter in mode).value
    val exclude = (excludeFilter in mode).value
    val inputFiles = resolveContexts(mode).value.flatMap(_.**(include && -exclude).get).filterNot(_.isDirectory)

    runUpdate(((config in mode).value +: inputFiles).toSet)
  }

  private def runNode(base: File, script: File, args: List[String], env: Map[String, String], log: Logger): Seq[JsValue] = {
    val resultBuffer = mutable.ArrayBuffer.newBuilder[JsValue]
    val exitValue = try {
      fork(
        "node" :: script.absolutePath :: args,
        base, env,
        log.info(_),
        log.error(_),
        line => resultBuffer += JsonParser(line)
      ).exitValue()
    } catch {
      case e: IOException => throw NodeMissingException(e)
    }
    if (exitValue != 0) {
      throw NodeExecuteFailureException(exitValue)
    }
    resultBuffer.result()
  }

  private def forkNode(base: File, script: File, args: List[String], env: Map[String, String], log: Logger): Process =
    try {
      fork("node" :: script.absolutePath :: args, base, env, log.info(_), log.error(_), _ => ())
    } catch {
      case e: IOException => throw NodeMissingException(e)
    }

  private val ResultEscapeChar: Char = 0x10

  private def fork(
    command: List[String], base: File, env: Map[String, String],
    processOutput: (String => Unit),
    processError: (String => Unit),
    processResult: (String => Unit)
  ): Process = {
    val io = new ProcessIO(
      writeInput = BasicIO.input(false),
      processOutput = BasicIO.processFully { line =>
        if (line.indexOf(ResultEscapeChar) == -1) {
          processOutput(line)
        } else {
          val (out, result) = line.span(_ != ResultEscapeChar)
          if (!out.isEmpty) {
            processOutput(out)
          }
          processResult(result.drop(1))
        }
      },
      processError = BasicIO.processFully(processError),
      inheritInput = BasicIO.inheritInput(false)
    )
    if (IS_OS_WINDOWS)
      Process("cmd" :: "/c" :: command, base, env.toSeq: _*).run(io)
    else
      Process(command, base, env.toSeq: _*).run(io)
  }
}
