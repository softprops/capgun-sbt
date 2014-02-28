package capgun

import sbt._
import sbt.Keys._

import org.json4s._
import org.json4s.JsonDSL._
import org.json4s.native.JsonMethods.{ compact, render }

import unfiltered.util.StartableServer
import unfiltered.netty._
import unfiltered.netty.websockets._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.annotation.tailrec
import scala.sys.process._

import java.io.File
import java.util.concurrent.atomic.AtomicReference

import scala.util.control.NonFatal

/** Serves persistant connections with websockets and notifies them of changes when fired */
case class Capgun(target: File, port: Int) {

  private val connections: mutable.Map[Int, WebSocket] =
    mutable.HashMap.empty[Int,WebSocket]

  private var _server: Option[StartableServer] = None

  def start(): Unit = {
    _server = Some(Http(port).plan(Planify {
      case _ => {
        case Open(s) =>
          connections += (s.channel.getId.toInt -> s)
        case Close(s) =>
          connections -= (s.channel.getId.toInt)
        case m =>
          println(s"unhandled msg $m")        
      }
    }).beforeStop {
      println("capgun is shutting down")
    }.start())
  }

  def listening: Boolean = _server.isDefined

  def stop() = _server.synchronized {
    _server.foreach(_.stop())
    _server = None
  }

  def fire(f: WebSocket => Unit) =
    connections.values.foreach(f)
}

object CapgunSbt extends sbt.Plugin {

  object CapgunKeys {
    val capgunStop  = taskKey[Unit]("stop capgun")
    val capgunStart = taskKey[Capgun]("start capgun")
    val capgunPort  = settingKey[Int]("port capgun listens on")
    val capgunFire  = taskKey[Unit]("notify capgun listeners")
  }
  import CapgunKeys._

  object CapgunState {
    val empty = CapgunState(Map.empty[ProjectRef, Capgun])
  }

  case class CapgunState(guns: Map[ProjectRef, Capgun]) {
    def add(project: ProjectRef, capgun: Capgun) =
      copy(guns = guns + (project -> capgun))
    def stop(project: ProjectRef) =
      guns.get(project).map(_.stop())
    def get(project: ProjectRef) =
      guns.get(project)
    def remove(project: ProjectRef) =
      copy(guns = guns - project)
  }

  object GlobalState {
    private[this] val state =
      new AtomicReference(CapgunState.empty)

    @tailrec def update(f: CapgunState => CapgunState): CapgunState = {
      val originalState = state.get()
      val newState = f(originalState)
      if (!state.compareAndSet(originalState, newState)) update(f)
      else newState
    }

    @tailrec def updateAndGet[T](f: CapgunState => (CapgunState, T)): T = {
      val originalState = state.get()
      val (newState, value) = f(originalState)
      if (!state.compareAndSet(originalState, newState)) updateAndGet(f)
      else value
    }

     def get(): CapgunState = state.get()
  }

  private def capgunState: CapgunState = GlobalState.get()

  private def start(
    streams: TaskStreams, target: File, project: ProjectRef, port: Int) = {
    if (capgunState.get(project).exists(_.listening)) sys.error(
      "capgun was already started") else {
        val capgun = Capgun(target, port)
        GlobalState.update { state =>
          state.stop(project)
          println(s"starting capgun on port $port")
          capgun.start()
          state.add(project, capgun)
        }
        capgun
      }
  }

  private def stop(
    streams: TaskStreams, project: ProjectRef) = {
    capgunState.get(project) match {
      case Some(capgun) =>
        println("stopping capgun")
        capgun.stop()
      case _ => println("capgun was not running")
    }
    GlobalState.update(_.remove(project))
  }

  private def restart(
    streams: TaskStreams, target: File, project: ProjectRef, port: Int) = {
    stop(streams, project)
    start(streams, target, project, port)
  }

  private def fire(streams: TaskStreams, project: ProjectRef, target: File, files: Seq[File]) =
    capgunState.get(project) match {
      case Some(capgun) =>
        println(s"firing capgun...")
        capgun.fire(_.send("pow"))
      case _ => println("capgun was not running")
    }

  def capgunSettings = seq(
    capgunPort := 9999,
    capgunStart <<= (
      streams, classDirectory in Compile, thisProjectRef, capgunPort)
      .map(restart)
      .dependsOn(products in Compile),
    capgunStop <<= (streams, thisProjectRef).map(stop),
    capgunFire <<= (streams, thisProjectRef, classDirectory in Compile, products in Compile).map(fire)
  )
}

