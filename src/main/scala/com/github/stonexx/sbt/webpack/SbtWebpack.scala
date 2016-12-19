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

    object WebpackKeys {
      val startWatch: TaskKey[Unit] = TaskKey[Unit]("startWatch", "Start the webpack module bundler for watch mode.")
      val stopWatch : TaskKey[Unit] = TaskKey[Unit]("stopWatch", "Stop the webpack module bundler for watch mode.")

      val config  : SettingKey[File]                   = SettingKey[File]("webpackConfig", "The location of a webpack configuration file.")
      val envVars : SettingKey[Map[String, String]]    = SettingKey[Map[String, String]]("webpackEnvVars", "Environment variable names and values to set for webpack.")
      val contexts: TaskKey[Seq[File]]                 = TaskKey[Seq[File]]("webpackContexts", "The locations of a webpack contexts.")
      val runner  : AttributeKey[WebpackWatcherRunner] = AttributeKey[WebpackWatcherRunner]("webpackWatcherRunner")
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

    implicit def stateToWebpackWatcherRunner(s: State): WebpackWatcherRunner = s.get(WebpackKeys.runner).get
  }

  import autoImport._
  import autoImport.WebpackKeys._

  private val webpackDependTasks: Seq[Scoped.AnyInitTask] = Seq(
    WebKeys.nodeModules in Plugin,
    WebKeys.nodeModules in Assets,
    WebKeys.webModules in Assets
  )

  override def projectSettings: Seq[Setting[_]] = Seq(
    includeFilter in Webpack := AllPassFilter,
    excludeFilter in Webpack := HiddenFileFilter,

    config := baseDirectory.value / "webpack.config.js",

    envVars := LocalEngine.nodePathEnv((WebKeys.nodeModuleDirectories in Plugin).value.map(_.getCanonicalPath).to[immutable.Seq]),
    envVars in run := envVars.value + ("NODE_ENV" -> "development"),
    envVars in Assets := envVars.value + ("NODE_ENV" -> "production"),
    envVars in TestAssets := envVars.value + ("NODE_ENV" -> "testing"),

    contexts in Assets := resolveContexts(Assets).value,
    contexts in TestAssets := resolveContexts(TestAssets).value,
    contexts := (contexts in Assets).value,

    run in Webpack in Assets := runWebpack(Assets).dependsOn(webpackDependTasks: _*).value,
    run in Webpack in TestAssets := runWebpack(TestAssets).dependsOn(webpackDependTasks: _*).value,
    run in Webpack := (run in Webpack in Assets).evaluated,

    startWatch in Webpack := state.value.start(new WebpackWatcher {
      private[this] var process: Option[Process] = None

      def start(): Unit = {
        state.value.log.info(s"start webpack watcher by ${relativizedPath(baseDirectory.value, config.value)}")

        IO.delete((streams in Webpack).value.cacheDirectory / "run")

        process = Some(forkNode(
          baseDirectory.value,
          getWebpackScript.value,
          List(
            config.value.absolutePath,
            URLEncoder.encode(JsObject("watch" -> JsBoolean(true)).toString, UTF_8)
          ),
          (envVars in run).value,
          state.value.log
        ))
      }

      def stop(): Unit = {
        state.value.log.info(s"stop webpack watcher by ${relativizedPath(baseDirectory.value, config.value)}")

        process.foreach(_.destroy())
        process = None
      }
    }),
    startWatch in Webpack := (startWatch in Webpack).dependsOn(webpackDependTasks: _*).value,
    stopWatch in Webpack := state.value.stop(),
    onLoad in Global := (onLoad in Global).value.andThen { state =>
      state.put(runner, new WebpackWatcherRunner)
    }
  )

  case class NodeMissingException(cause: Throwable) extends RuntimeException("'node' is required. Please install it and add it to your PATH.", cause)
  case class NodeExecuteFailureException(exitValue: Int) extends RuntimeException("Failed to execute node.")

  private def getWebpackScript: Def.Initialize[Task[File]] = Def.task {
    SbtWeb.copyResourceTo(
      (target in Plugin).value / Webpack.name,
      getClass.getClassLoader.getResource("webpack.js"),
      streams.value.cacheDirectory / "copy-resource"
    )
  }

  private def resolveContexts(config: Configuration): Def.Initialize[Task[Seq[File]]] = Def.task {
    val resolveContextsScript = SbtWeb.copyResourceTo(
      (target in Plugin).value / Webpack.name,
      getClass.getClassLoader.getResource("resolve-contexts.js"),
      streams.value.cacheDirectory / "copy-resource"
    )
    val results = runNode(
      baseDirectory.value,
      resolveContextsScript,
      List(WebpackKeys.config.value.absolutePath),
      (envVars in config).value,
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

  private def runWebpack(config: Configuration): Def.Initialize[Task[Unit]] = Def.task {
    val configFile = WebpackKeys.config.value

    val cacheDir = streams.value.cacheDirectory / "run" / config.name
    val runUpdate = cached(cacheDir, FilesInfo.hash) { _ =>
      state.value.log.info(s"Webpack running by ${relativizedPath(baseDirectory.value, configFile)}")

      runNode(
        baseDirectory.value,
        getWebpackScript.value,
        List(
          configFile.absolutePath,
          URLEncoder.encode(JsObject("watch" -> JsBoolean(false)).toString, UTF_8)
        ),
        (envVars in config).value,
        state.value.log
      )

      doClean(cacheDir.getParentFile.*(DirectoryFilter).get, Seq(cacheDir))
    }

    val include = (includeFilter in Webpack in config).value
    val exclude = (excludeFilter in Webpack in config).value
    val inputFiles = (contexts in config).value.flatMap(_.**(include && -exclude).get).filterNot(_.isDirectory)

    runUpdate((configFile +: inputFiles).toSet)
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
