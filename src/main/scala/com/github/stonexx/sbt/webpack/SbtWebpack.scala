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
    val webpack: InputKey[Unit] = inputKey[Unit]("Webpack module bundler.")

    object WebpackModes {
      val Dev : Configuration = config("dev")
      val Prod: Configuration = config("prod")
      val Test: Configuration = config("test")
    }

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
    includeFilter in (WebpackModes.Dev, webpack) := (includeFilter in webpack).value,
    includeFilter in (WebpackModes.Prod, webpack) := (includeFilter in webpack).value,
    includeFilter in (WebpackModes.Test, webpack) := (includeFilter in webpack).value,

    excludeFilter in webpack := HiddenFileFilter,
    excludeFilter in (WebpackModes.Dev, webpack) := (excludeFilter in webpack).value,
    excludeFilter in (WebpackModes.Prod, webpack) := (excludeFilter in webpack).value,
    excludeFilter in (WebpackModes.Test, webpack) := (excludeFilter in webpack).value,

    config in webpack := baseDirectory.value / "webpack.config.js",
    config in (WebpackModes.Dev, webpack) := (config in webpack).value,
    config in (WebpackModes.Prod, webpack) := (config in webpack).value,
    config in (WebpackModes.Test, webpack) := (config in webpack).value,

    envVars in webpack := LocalEngine.nodePathEnv((WebKeys.nodeModuleDirectories in Plugin).value.map(_.getCanonicalPath).to[immutable.Seq]),
    envVars in (WebpackModes.Dev, webpack) := (envVars in webpack).value + ("NODE_ENV" -> "development"),
    envVars in (WebpackModes.Prod, webpack) := (envVars in webpack).value + ("NODE_ENV" -> "production"),
    envVars in (WebpackModes.Test, webpack) := (envVars in webpack).value + ("NODE_ENV" -> "testing"),

    webpack := Def.inputTaskDyn {
      import complete.DefaultParsers._

      val arg = (EOF | Seq(
        "dev",
        "prod",
        "test",
        "watch",
        "stop"
      ).map(t => Space ~ token(t)).reduce(_ | _).map(_._2)).parsed

      val cacheDir = streams.value.cacheDirectory

      arg match {
        case "dev" => Def.taskDyn(runWebpack(cacheDir, WebpackModes.Dev).dependsOn(webpackDependTasks: _*))
        case "prod" | () => Def.taskDyn(runWebpack(cacheDir, WebpackModes.Prod).dependsOn(webpackDependTasks: _*))
        case "test" => Def.taskDyn(runWebpack(cacheDir, WebpackModes.Test).dependsOn(webpackDependTasks: _*))
        case "watch" => Def.taskDyn(webpackWatchStart(cacheDir).dependsOn(webpackDependTasks: _*))
        case "stop" => Def.taskDyn(webpackWatchStop)
      }
    }.evaluated,

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

  private def getWebpackScript(cacheDir: File): Def.Initialize[Task[File]] = Def.task {
    SbtWeb.copyResourceTo(
      (target in Plugin).value / webpack.key.label,
      getClass.getClassLoader.getResource("webpack.js"),
      cacheDir / "get-webpack-script"
    )
  }

  private def getContexts(cacheDir: File, mode: Configuration): Def.Initialize[Task[Seq[File]]] = Def.task {
    val getContextsScript = SbtWeb.copyResourceTo(
      (target in Plugin).value / webpack.key.label,
      getClass.getClassLoader.getResource("contexts.js"),
      cacheDir / "get-contexts-script"
    )
    val results = runNode(
      baseDirectory.value,
      getContextsScript,
      List((config in (mode, webpack)).value.absolutePath),
      (envVars in (mode, webpack)).value,
      state.value.log
    )
    import DefaultJsonProtocol._
    results.headOption.toList.flatMap(_.convertTo[Seq[String]]).map(path => baseDirectory.value / path)
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

  private def webpackWatchStart(cacheDir: File): Def.Initialize[Task[Unit]] = Def.task {
    state.value.start(new WebpackWatcher {
      private[this] var process: Option[Process] = None

      def start(): Unit = {
        state.value.log.info(s"Starting webpack watcher by ${relativizedPath(baseDirectory.value, (config in (WebpackModes.Dev, webpack)).value)}")

        IO.delete(cacheDir / "run")

        process = Some(forkNode(
          baseDirectory.value,
          getWebpackScript(cacheDir).value,
          List(
            (config in (WebpackModes.Dev, webpack)).value.absolutePath,
            URLEncoder.encode(JsObject("watch" -> JsBoolean(true)).toString, UTF_8)
          ),
          (envVars in (WebpackModes.Dev, webpack)).value,
          state.value.log
        ))
      }

      def stop(): Unit = {
        state.value.log.info(s"Stopping webpack watcher by ${relativizedPath(baseDirectory.value, (config in (WebpackModes.Dev, webpack)).value)}")

        process.foreach(_.destroy())
        process = None
      }
    })
  }

  private def webpackWatchStop: Def.Initialize[Task[Unit]] = Def.task(state.value.stop())

  private def runWebpack(cacheDir: File, mode: Configuration): Def.Initialize[Task[Unit]] = Def.task {
    state.value.get(watcherRunner).foreach(_.stop())

    Seq(
      WebpackModes.Dev,
      WebpackModes.Prod,
      WebpackModes.Test
    ).filter(_ != mode).foreach { m =>
      IO.delete(cacheDir / "run" / m.name)
    }

    val runCacheDir = cacheDir / "run" / mode.name
    val runUpdate = cached(runCacheDir, FilesInfo.hash) { _ =>
      state.value.log.info(s"Running ${mode.name} by ${relativizedPath(baseDirectory.value, (config in (mode, webpack)).value)}")

      runNode(
        baseDirectory.value,
        getWebpackScript(cacheDir).value,
        List(
          (config in (mode, webpack)).value.absolutePath,
          URLEncoder.encode(JsObject("watch" -> JsBoolean(false)).toString, UTF_8)
        ),
        (envVars in (mode, webpack)).value,
        state.value.log
      )

      doClean(runCacheDir.getParentFile.*(DirectoryFilter).get, Seq(runCacheDir))
    }

    val include = (includeFilter in (mode, webpack)).value
    val exclude = (excludeFilter in (mode, webpack)).value
    val inputFiles = getContexts(cacheDir, mode).value.flatMap(_.**(include && -exclude).get).filterNot(_.isDirectory)

    runUpdate(((config in (mode, webpack)).value +: inputFiles).toSet)
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

