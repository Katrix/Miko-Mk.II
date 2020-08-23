import Vue from 'vue'
import config from '../config'

const guestState = {
  playlist: [
    {
      id: 0,
      url: 'https://www.youtube.com/watch?v=fNWghDrW_UU',
      name: 'Feel The Melody - S3RL feat Sara',
      duration: 238,
      isSeekable: true,
    },
    {
      id: 1,
      url: 'https://www.youtube.com/watch?v=5kKnrjeDUqw',
      name: "gmtn (witch's slave) - furioso melodia",
      duration: 354,
      isSeekable: true,
    },
    {
      id: 2,
      url: 'https://www.youtube.com/watch?v=kZrNHq0NmRk',
      name: 'It Went - S3RL feat Tamika',
      duration: 226,
      isSeekable: true,
    },
    {
      id: 3,
      url: 'https://www.youtube.com/watch?v=9DF-72FxwEk',
      name: '24/7 Nightcore Music Radio | Best Gaming Music ~ \uD83D\uDC51',
      duration: NaN,
      isSeekable: false,
    },
  ],
  idx: 0,
  isPaused: false,
  playingCurrentPos: 100,
  volume: 100,
  defVolume: 100,
  loop: false,
}

const state = {
  loading: false,
  playlist: [],
  idx: 0,
  isPaused: false,
  playingCurrentPos: 0,
  volume: 100,
  defVolume: 100,
  loop: false,
}

const mutations = {
  setGuestData(state) {
    Vue.set(state, 'playlist', [...guestState.playlist])
    state.idx = guestState.idx
    state.isPaused = guestState.isPaused
    state.playingCurrentPos = guestState.playingCurrentPos
    state.volume = guestState.volume
    state.defVolume = guestState.defVolume
    state.loop = guestState.loop
    state.loading = false
  },
  setMusicData(state, { data }) {
    Vue.set(state, 'playlist', data.playlist)
    state.idx = data.idx
    state.isPaused = data.isPaused
    state.playingCurrentPos = data.playingCurrentPos
    state.volume = data.volume
    state.defVolume = data.defVolume
    state.loop = data.loop
    state.loading = false
  },
  updatePlaylist(state, { playlist }) {
    Vue.set(state, 'playlist', playlist)
  },
  setTrackPlaying(state, { idx, position }) {
    state.idx = idx
    state.playingCurrentPos = position
  },
  updateVolume(state, { volume, defVolume }) {
    state.volume = volume
    state.defVolume = defVolume
  },
  updatePaused(state, { paused }) {
    state.paused = paused
  },
  setPosition(state, { position }) {
    state.playingCurrentPos = position
  },
  addTracks(state, { tracks }) {
    state.playlist.push(...tracks)
  },
  nextTrack(state) {
    state.idx++
  },
  setLoop(state, { loop }) {
    state.loop = loop
  },
  clearData(state) {
    state.playlist = []
    state.idx = 0
    state.isPaused = false
    state.playingCurrentPos = 0
    state.volume = 100
    state.defVolume = 100
    state.loop = false
    state.loading = true
  },
}

let websocketSession = null

// Server OPCodes
const S_NEXT_TRACK = 0
const S_ADD_TRACKS = 1
const S_SET_POSITION = 2
const S_SET_PAUSED = 3
const S_UPDATE_VOLUME = 4
const S_REFRESH_STATE = 5
const S_SET_PLAYLIST = 6
const S_SET_TRACK_PLAYING = 7
const S_SET_LOOP = 8
const S_STOPPING = 9

// Client OPCodes
const C_SET_POSITION = 12
const C_SET_PAUSED = 13
const C_UPDATE_VOLUME = 14
const C_SET_PLAYLIST = 16
const C_SET_TRACK_PLAYING = 17

// TODO: Communicate with backend through WS
const actions = {
  startClient(context) {
    if (
      !context.state.loading ||
      websocketSession.readyState === WebSocket.CONNECTING ||
      websocketSession.readyState === WebSocket.OPEN
    ) {
      return
    }
    /*
    const guildId = context.rootState.guild.activeGuildId

    websocketSession = new WebSocket(`${config.wsBackendBaseUrl}api/guild/${guildId}/music/ws`)

    websocketSession.onmessage = function (ev) {
      const data = JSON.parse(ev.data)
      switch (data.op) {
        case S_REFRESH_STATE:
          context.commit({
            type: 'setMusicData',
            data,
          })
          break
        case S_SET_PLAYLIST:
          context.commit({
            type: 'updatePlaylist',
            playlist: data,
          })
          break
        case S_SET_TRACK_PLAYING:
          context.commit({
            type: 'setTrackPlaying',
            idx: data.idx,
            position: data.position,
          })
          break
        case S_UPDATE_VOLUME:
          context.commit({
            type: 'updateVolume',
            volume: data.volume,
            defVolume: data.defVolume,
          })
          break
        case S_SET_PAUSED:
          context.commit({ type: 'updatePaused', paused: data.paused })
          break
        case S_SET_POSITION:
          context.commit({ type: 'setPosition', position: data.position })
          break
        case S_ADD_TRACKS:
          context.commit({ type: 'addTracks', tracks: data.tracks })
          break
        case S_NEXT_TRACK:
          context.commit('nextTrack')
          break
        case S_SET_LOOP:
          context.commit({ type: 'setLoop', loop: data.loop })
          break
        case S_STOPPING:
          //TODO
          break
      }
    }
     */
  },
  resetConnection(context) {
    context.commit('clearData')
    if (websocketSession) {
      websocketSession.close(1001)
      websocketSession = null
    }
  },
  movePlaylistEntry(context, { idx, by }) {
    // TODO WS
    let offset = by
    if (context.state.idx === idx + by) {
      offset += Math.sign(by)
    }

    const tmp = context.state.playlist[idx + offset]
    Vue.set(context.state.playlist, idx + offset, context.state.playlist[idx])
    Vue.set(context.state.playlist, idx, tmp)
  },
  removePlaylistEntry(context, { idx }) {
    // TODO WS
    context.state.playlist.splice(idx, 1)
  },
  setVolume(context, payload) {
    websocketSession.send(
      JSON.stringify({
        op: C_UPDATE_VOLUME,
        volume: payload.volume ?? context.state.volume,
        defVolume: payload.defVolume ?? context.state.defVolume,
      })
    )
  },
  setPlaying() {
    websocketSession.send(JSON.stringify({ op: C_SET_PAUSED, paused: false }))
  },
  setPaused() {
    websocketSession.send(JSON.stringify({ op: C_SET_PAUSED, paused: true }))
  },
  prevTrack(context) {
    websocketSession.send(JSON.stringify({ op: C_SET_TRACK_PLAYING, idx: context.state.idx - 1, position: 0 }))
  },
  nextTrack(context) {
    websocketSession.send(JSON.stringify({ op: C_SET_TRACK_PLAYING, idx: context.state.idx + 1, position: 0 }))
  },
}

export default {
  namespaced: true,
  state,
  mutations,
  actions,
}
