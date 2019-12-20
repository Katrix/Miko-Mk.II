package miko.pages

import scala.concurrent.Future
import scala.scalajs.js
import scala.concurrent.duration._

import org.scalajs.dom.ext._
import org.scalajs.dom.html.{Input, Progress}
import org.scalajs.dom.{Element, document, html, window}

import ackcord.data.GuildId
import miko.Implicits._

object Music {

  var currentTime = 0
  var totalTime   = 0

  //https://stackoverflow.com/questions/6312993/javascript-seconds-to-time-string-with-format-hhmmss?page=2&tab=votes#tab-top
  def prettyDuration(totalSeconds: Int): String = {
    val hours   = totalSeconds / 3600
    val minutes = (totalSeconds - (hours * 3600)) / 60
    val seconds = totalSeconds - (hours * 3600) - (minutes * 60)

    val builder = new StringBuilder
    if (hours > 0) builder.append(hours).append(':')

    if (minutes < 10 && hours > 0)
      builder.append("0" + minutes).append(':')
    else
      builder.append(minutes).append(':')

    if (seconds < 10)
      builder.append("0" + seconds)
    else
      builder.append(seconds)

    builder.mkString
  }

  def setupMusic(guildId: GuildId): Unit = {
    val progressElem    = document.getElementById("musicProgress").asInstanceOf[Progress]
    val currentTimeElem = document.getElementById("currentTime").asInstanceOf[Progress]
    val totalTimeElem   = document.getElementById("totalTime").asInstanceOf[Progress]

    js.timers.setInterval(1.second) {
      currentTimeElem.innerHTML = prettyDuration(currentTime)
      totalTimeElem.innerHTML = prettyDuration(totalTime)
      progressElem.value = currentTime
      progressElem.max = totalTime
    }

    val defaultVolume = document.getElementById("musicPlayingDefaultVolume").asInstanceOf[Input]
    val currentVolume = document.getElementById("musicPlayingCurrentVolume").asInstanceOf[Input]

    defaultVolume.onchange = e => {
      val volume = e.target.asInstanceOf[Input].valueAsNumber.toInt
      sendDefaultVolume(guildId, volume)
    }

    currentVolume.onchange = e => {
      val volume = e.target.asInstanceOf[Input].valueAsNumber.toInt
      sendCurrentVolume(guildId, volume)
    }

    val stepBackward = document.getElementById("musicStepBackward").asInstanceOf[html.Element]
    val stepForward  = document.getElementById("musicStepForward").asInstanceOf[html.Element]
    val play         = document.getElementById("musicPlay").asInstanceOf[html.Element]
    val pause        = document.getElementById("musicPause").asInstanceOf[html.Element]

    stepBackward.onclick = _ => {
      sendStepBackward(guildId)
    }

    stepForward.onclick = _ => {
      sendStepForward(guildId)
    }

    play.onclick = _ => {
      sendPlay(guildId)
    }

    pause.onclick = _ => {
      sendPause(guildId)
    }
  }

  def sendDefaultVolume(guildId: GuildId, volume: Int): Unit =
    println(s"Sending set default volume to $volume for $guildId")

  def sendCurrentVolume(guildId: GuildId, volume: Int): Unit =
    println(s"Sending set current volume to $volume for $guildId")

  def sendStepBackward(guildId: GuildId): Unit = println(s"Sending step backward for $guildId")

  def sendStepForward(guildId: GuildId): Unit = println(s"Sending step forward for $guildId")

  def sendPlay(guildId: GuildId): Unit = println(s"Sending play for $guildId")

  def sendPause(guildId: GuildId): Unit = println(s"Sending pause for $guildId")
}
