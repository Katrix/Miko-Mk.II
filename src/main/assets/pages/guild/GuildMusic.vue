<template>
  <div class="flex-grow-1">
    <div class="h-100 d-flex flex-column">
      <div class="row flex-grow-1 no-gutters">
        <div
          class="col-md-8 music-playerimg"
          :style="{
            'background-image': currentlyPlaying ? `url(${imgUrl(playlist[idx].url, true)})` : undefined,
            'background-color': 'gray',
          }"
        ></div>
        <div class="col-md-4 bg-dark">
          <div class="player-playlist">
            <b-media
              v-for="(item, itemIdx) in playlist"
              :key="item.id"
              class="player-playlist-item text-white"
              no-body
              :style="[idx === itemIdx && 'background-color: #343a40']"
            >
              <b-media-aside class="mr-2">
                <img :src="imgUrl(item.url)" width="128px" height="72px" :alt="item.name" />
              </b-media-aside>

              <b-media-body>
                <strong>{{ item.name }}</strong>
                <br />
                <small>{{ item.url }}</small>
                <br />
                {{ item.isSeekable ? formatDuration(item.duration) : 'Radio' }}
              </b-media-body>

              <b-media-aside class="mr-2">
                <div class="btn-group">
                  <button
                    class="btn btn-outline-light"
                    aria-label="Move entry up"
                    :disabled="itemIdx === 0 || itemIdx === idx"
                    @click="movePlaylist(itemIdx, -1)"
                  >
                    <font-awesome-icon :icon="['fas', 'arrow-up']" />
                  </button>
                  <button
                    class="btn btn-outline-light"
                    aria-label="Move entry down"
                    :disabled="itemIdx === playlist.length - 1 || itemIdx === idx"
                    @click="movePlaylist(itemIdx, 1)"
                  >
                    <font-awesome-icon :icon="['fas', 'arrow-down']" />
                  </button>
                  <button class="btn btn-danger" aria-label="Remove entry" @click="removePlaylistEntry(itemIdx)">
                    <font-awesome-icon :icon="['fas', 'times']" />
                  </button>
                </div>
              </b-media-aside>
            </b-media>
          </div>
        </div>
      </div>
      <div class="player-bar">
        <div v-if="playlist[idx].isSeekable" class="player-item">
          <b-progress :value="playingCurrentPos" :max="playlist[idx].duration"></b-progress>
        </div>
        <div class="player-item d-flex justify-content-center">
          <div class="form-inline" style="font-size: 1.25rem; padding: 0 0.5em;">
            <div class="form-group">
              <label for="defVolumeInput">Default volume: </label>
              <output for="defVolumeInput">{{ defVolume }}</output>
              <b-form-input id="defVolumeInput" v-model="defVolume" type="range" min="50" max="150" step="1" number />
            </div>
          </div>
          <div style="padding: 0 0.5em;">
            <font-awesome-icon
              :icon="['fas', 'step-backward']"
              size="lg"
              role="button"
              aria-label="step backward"
              @click="idx !== 0 && $store.dispatch('guild/music/prevTrack')"
            />
          </div>
          <div style="padding: 0 0.5em;">
            <font-awesome-icon
              :icon="['fas', 'play']"
              size="lg"
              role="button"
              aria-label="play music"
              @click="$store.dispatch('guild/music/setPlaying')"
            />
            <font-awesome-icon
              :icon="['fas', 'pause']"
              size="lg"
              role="button"
              aria-label="pause music"
              @click="$store.dispatch('guild/music/setPaused')"
            />
            <div class="time d-inline-block">
              <span class="current-time">{{ formatDuration(playingCurrentPos) }}</span>
              <span class="seperator-time">/</span>
              <span class="total-time">{{ formatDuration(playlist[idx].duration || 0) }}</span>
            </div>
          </div>
          <div style="padding: 0 0.5em;">
            <font-awesome-icon
              :icon="['fas', 'step-forward']"
              size="lg"
              role="button"
              aria-label="step forward"
              @click="$store.dispatch('guild/music/nextTrack')"
            />
          </div>
          <div class="form-inline" style="font-size: 1.25rem; padding: 0 0.5em;">
            <div class="form-group">
              <label for="currentVolumeInput">Current Volume: </label>
              <output for="currentVolumeInput">{{ volume }}</output>
              <b-form-input id="currentVolumeInput" v-model="volume" type="range" min="50" max="150" step="1" number />
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { mapState } from 'vuex'
import { BMedia, BMediaAside, BMediaBody, BProgress, BFormInput } from 'bootstrap-vue'

export default {
  components: {
    BMedia,
    BMediaAside,
    BMediaBody,
    BProgress,
    BFormInput,
  },
  computed: {
    currentlyPlaying() {
      return this.playlist.length
    },
    ...mapState('guild/music', ['playlist', 'idx', 'isPaused', 'playingCurrentPos']),
    volume: {
      get() {
        return this.$store.state.guild.music.volume
      },
      set(newVal) {
        this.$store.dispatch({
          type: 'guild/music/setVolume',
          volume: newVal,
        })
      },
    },
    defVolume: {
      get() {
        return this.$store.state.guild.music.defVolume
      },
      set(newVal) {
        this.$store.dispatch({
          type: 'guild/music/setVolume',
          defVolume: newVal,
        })
      },
    },
  },
  beforeCreate() {
    this.$store.dispatch('guild/music/startClient')
  },
  mounted() {
    this.$store.commit({
      type: 'ui/setTopStyle',
      style: 'height: 100vh; display: flex; flex-direction: column;',
    })
  },
  destroyed() {
    this.$store.commit('ui/clearTopStyle')
  },
  methods: {
    ytImgUrl(id, highQuality) {
      if (highQuality) {
        return `https://img.youtube.com/vi/${id}/0.jpg`
      } else {
        return `https://img.youtube.com/vi/${id}/mqdefault.jpg`
      }
    },
    imgUrl(url, highQuality) {
      const uri = new URL(url)
      if (uri.host === 'www.youtube.com' || uri.host === 'youtube.com') {
        const v = uri.searchParams.get('v')
        return v ? this.ytImgUrl(v, highQuality) : ''
      } else if (uri.host === 'youtu.be') {
        return this.ytImgUrl(uri.pathname.substring(1))
      } else {
        // eslint-disable-next-line no-console
        console.warn(uri.host)
        return ''
      }
    },
    formatDuration(duration) {
      // https://stackoverflow.com/questions/6312993/javascript-seconds-to-time-string-with-format-hhmmss
      let hours = Math.floor(duration / 3600)
      let minutes = Math.floor((duration - hours * 3600) / 60)
      let seconds = duration - hours * 3600 - minutes * 60

      if (hours < 10) {
        hours = '0' + hours
      }
      if (minutes < 10) {
        minutes = '0' + minutes
      }
      if (seconds < 10) {
        seconds = '0' + seconds
      }

      if (hours > 0) {
        return hours + ':' + minutes + ':' + seconds
      } else {
        return minutes + ':' + seconds
      }
    },
    movePlaylist(idx, by) {
      this.$store.dispatch({
        type: 'guild/music/movePlaylistEntry',
        idx,
        by,
      })
    },
    removePlaylistEntry(idx) {
      this.$store.dispatch({
        type: 'guild/music/removePlaylistEntry',
        idx,
      })
    },
  },
}
</script>
