sbtPlugin := true

organization := "me.lessis"

name := "capgun"

libraryDependencies ++=  Seq(
  "net.databinder" %% "unfiltered-netty-websockets" % "0.7.0",
  "org.json4s" %% "json4s-native" % "3.2.7")
