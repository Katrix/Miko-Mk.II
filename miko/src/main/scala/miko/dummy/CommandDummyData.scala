package miko.dummy

import miko.services.{CommandData, CommandEntry, MikoCmdCategory}

object CommandDummyData
    extends CommandData(
      Map(
        MikoCmdCategory("!", "General commands") -> Set(
          CommandEntry("Foo", Seq("f", "foo"), "<foo> <bar>", "Foo someone"),
          CommandEntry("Bar", Seq("br", "bar"), "", "Bar everything"),
          CommandEntry("Baz", Seq("bz", "baz"), "<args...>", "Baz nothing")
        ),
        MikoCmdCategory("&", "Music commands") -> Set(
          CommandEntry("Queue", Seq("queue", "q"), "<identifier>", "Queue a track"),
          CommandEntry("Pause", Seq("pause", "p"), "", "Toggle pause on the playing track")
        )
      )
    )
