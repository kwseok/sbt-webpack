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
    val webpack     : TaskKey[Unit] = taskKey[Unit]("Start the webpack module bundler.")
    val webpackDev  : TaskKey[Unit] = taskKey[Unit]("Start the webpack module bundler for development mode.")
    val webpackProd : TaskKey[Unit] = taskKey[Unit]("Start the webpack module bundler for production mode.")
    val webpackTest : TaskKey[Unit] = taskKey[Unit]("Start the webpack module bundler for testing mode.")
    val webpackWatch: TaskKey[Unit] = taskKey[Unit]("Start the webpack module bundler for watch mode.")
    val webpackStop : TaskKey[Unit] = taskKey[Unit]("Stop the webpack module bundler for watch mode.")

    object WebpackKeys {
      val config       : SettingKey[File]                   = SettingKey[File]("webpackConfig", "The location of a webpack configuration file.")
      val envVars      : SettingKey[Map[String, String]]    = SettingKey[Map[String, String]]("webpackEnvVars", "Environment variable names and values to set for webpack.")
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

  private val webpackDependTasks: Seq[Scoped.AnyInitTask] = Seq(
    WebKeys.nodeModules in Plugin,
    WebKeys.nodeModules in Assets,
    WebKeys.webModules in Assets
  )

  override def projectSettings: Seq[Setting[_]] = Seq(
    includeFilter in webpack := AllPassFilter,
    includeFilter in webpackDev := (includeFilter in webpack).value,
    includeFilter in webpackProd := (includeFilter in webpack).value,
    includeFilter in webpackTest := (includeFilter in webpack).value,

    excludeFilter in webpack := HiddenFileFilter,
    excludeFilter in webpackDev := (excludeFilter in webpack).value,
    excludeFilter in webpackProd := (excludeFilter in webpack).value,
    excludeFilter in webpackTest := (excludeFilter in webpack).value,

    config in webpack := baseDirectory.value / "webpack.config.js",
    config in webpackDev := (config in webpack).value,
    config in webpackProd := (config in webpack).value,
    config in webpackTest := (config in webpack).value,

    envVars in webpack := LocalEngine.nodePathEnv((WebKeys.nodeModuleDirectories in Plugin).value.map(_.getCanonicalPath).to[immutable.Seq]),
    envVars in webpackDev := (envVars in webpack).value + ("NODE_ENV" -> "development"),
    envVars in webpackProd := (envVars in webpack).value + ("NODE_ENV" -> "production"),
    envVars in webpackTest := (envVars in webpack).value + ("NODE_ENV" -> "testing"),

    webpack := webpackProd.value,
    webpackDev := runWebpack(webpackDev).dependsOn(webpackDependTasks: _*).value,
    webpackProd := runWebpack(webpackProd).dependsOn(webpackDependTasks: _*).value,
    webpackTest := runWebpack(webpackTest).dependsOn(webpackDependTasks: _*).value,

    webpackWatch := state.value.start(new WebpackWatcher {
      private[this] var process: Option[Process] = None

      def start(): Unit = {
        state.value.log.info(s"Starting webpack watcher by ${relativizedPath(baseDirectory.value, (config in webpackDev).value)}")

        IO.delete(Seq(
          (streams in webpackDev).value.cacheDirectory / "run",
          (streams in webpackProd).value.cacheDirectory / "run",
          (streams in webpackTest).value.cacheDirectory / "run"
        ))

        process = Some(forkNode(
          baseDirectory.value,
          getWebpackScript.value,
          List(
            (config in webpackDev).value.absolutePath,
            URLEncoder.encode(JsObject("watch" -> JsBoolean(true)).toString, UTF_8)
          ),
          (envVars in webpackDev).value,
          state.value.log
        ))
      }

      def stop(): Unit = {
        state.value.log.info(s"Stopping webpack watcher by ${relativizedPath(baseDirectory.value, (config in webpackDev).value)}")

        process.foreach(_.destroy())
        process = None
      }
    }),
    webpackWatch := webpackWatch.dependsOn(webpackDependTasks: _*).value,
    webpackStop := state.value.stop(),
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
      (target in Plugin).value / webpack.key.label,
      getClass.getClassLoader.getResource("webpack.js"),
      (streams in webpack).value.cacheDirectory / "copy-resource" / "webpack"
    )
  }

  private def resolveContexts(scoped: Scoped): Def.Initialize[Task[Seq[File]]] = Def.task {
    val resolveContextsScript = SbtWeb.copyResourceTo(
      (target in Plugin).value / webpack.key.label,
      getClass.getClassLoader.getResource("resolve-contexts.js"),
      (streams in webpack).value.cacheDirectory / "copy-resource" / "resolve-contexts"
    )
    val results = runNode(
      baseDirectory.value,
      resolveContextsScript,
      List((config in scoped).value.absolutePath),
      (envVars in scoped).value,
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

  private def runWebpack(scoped: Scoped): Def.Initialize[Task[Unit]] = Def.task {
    state.value.get(watcherRunner).foreach(_.stop())

    if (scoped != webpackDev) IO.delete((streams in webpackDev).value.cacheDirectory / "run")
    if (scoped != webpackProd) IO.delete((streams in webpackProd).value.cacheDirectory / "run")
    if (scoped != webpackTest) IO.delete((streams in webpackTest).value.cacheDirectory / "run")

    val cacheDir = (streams in scoped).value.cacheDirectory / "run"
    val runUpdate = cached(cacheDir, FilesInfo.hash) { _ =>
      state.value.log.info(s"Running ${scoped.key.label} by ${relativizedPath(baseDirectory.value, (config in scoped).value)}")

      runNode(
        baseDirectory.value,
        getWebpackScript.value,
        List(
          (config in scoped).value.absolutePath,
          URLEncoder.encode(JsObject("watch" -> JsBoolean(false)).toString, UTF_8)
        ),
        (envVars in scoped).value,
        state.value.log
      )

      doClean(cacheDir.getParentFile.*(DirectoryFilter).get, Seq(cacheDir))
    }

    val include = (includeFilter in scoped).value
    val exclude = (excludeFilter in scoped).value
    val inputFiles = resolveContexts(scoped).value.flatMap(_.**(include && -exclude).get).filterNot(_.isDirectory)

    runUpdate(((config in scoped).value +: inputFiles).toSet)
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

