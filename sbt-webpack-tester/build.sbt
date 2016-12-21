lazy val root = (project in file(".")).enablePlugins(SbtWeb)

JsEngineKeys.engineType := JsEngineKeys.EngineType.Node

WebKeys.pipeline := WebKeys.pipeline.dependsOn(webpack).value
