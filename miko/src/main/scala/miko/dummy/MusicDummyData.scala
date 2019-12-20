package miko.dummy

import miko.services.{MusicData, MusicEntry, MusicState}

object MusicDummyData extends MusicData(
  MusicState(
    volume = 90, defVolume = 50, paused = false, currentPosition = 43, userInVoiceChannel = true
  ),
  Seq(
    MusicEntry(
      0,
      "https://www.youtube.com/watch?v=fNWghDrW_UU",
      "Feel The Melody - S3RL feat Sara",
      238,
      isSeekable = true
    ),
    MusicEntry(
      1,
      "https://www.youtube.com/watch?v=5kKnrjeDUqw",
      "gmtn (witch's slave) - furioso melodia",
      354,
      isSeekable = true
    ),
    MusicEntry(
      2,
      "https://www.youtube.com/watch?v=kZrNHq0NmRk",
      "It Went - S3RL feat Tamika",
      226,
      isSeekable = true
    ),
    MusicEntry(
      3,
      "https://www.youtube.com/watch?v=9DF-72FxwEk",
      "24/7 Nightcore Music Radio | Best Gaming Music ~ \uD83D\uDC51 ",
      Int.MaxValue,
      isSeekable = false
    )
  ),
  Some(1)
)
