import Vue from 'vue'
import { API } from '../api'

const guestState = {
  isLoading: false,
  channels: {
    botSpamChannel: null,
    staffChannel: null,
  },
  music: {
    defaultMusicVolume: 100,
  },
  voiceText: {
    enabled: false,
    blacklist: {
      channels: [],
      categories: [],
    },
    dynamicallyResizeChannels: 0,
    destructive: {
      enabled: false,
      saveDestroyed: false,
      blacklist: [],
    },
    perms: {
      global: {
        everyone: {
          outside: {
            allow: [],
            deny: [],
          },
          inside: {
            allow: [],
            deny: [],
          },
        },
        users: {},
        roles: {},
      },
      overrideChannel: {},
      overrideCategory: {},
    },
  },
  commands: {
    requiresMention: false,
    prefixes: {
      general: ['m!'],
      music: ['mm!'],
    },
    permissions: {
      general: {
        categoryWide: 'disallow',
        categoryMergeOperation: 'or',
        cleanup: 'allow',
        shiftChannels: 'allow',
        info: 'allow',
        safebooru: 'allow',
      },
      music: {
        categoryWide: 'disallow',
        categoryMergeOperation: 'or',
        pause: 'allow',
        volume: 'allow',
        defVolume: 'allow',
        stop: 'allow',
        nowPlaying: 'allow',
        queue: 'allow',
        next: 'allow',
        prev: 'allow',
        clear: 'allow',
        shuffle: 'allow',
        ytQueue: 'allow',
        scQueue: 'allow',
        gui: 'allow',
        seek: 'allow',
        progress: 'allow',
        loop: 'allow',
      },
    },
  },
  modLog: {
    channelId: null,
    ignoredAuditLogEvents: []
  }
}

const state = {
  ...guestState,
}

function useGustSettings(state) {
  Vue.set(state, 'channels', guestState.channels)
  Vue.set(state, 'music', guestState.music)
  Vue.set(state, 'voiceText', guestState.voiceText)
  Vue.set(state, 'commands', guestState.commands)
}

const mutations = {
  setGuildSettings(state, { settings }) {
    Vue.set(state, 'channels', settings.channels)
    Vue.set(state, 'music', settings.music)
    Vue.set(state, 'voiceText', settings.voiceText)
    Vue.set(state, 'commands', settings.commands)
    state.isLoading = false
  },
  clearSettings(state) {
    useGustSettings(state)
    state.isLoading = true
  },
  setGuestSettings(state) {
    useGustSettings(state)
    state.isLoading = false
  },
}

const actions = {
  async updateSettings(context, { settings }) {
    await API.request(`guild/${context.rootState.guild.activeGuildId}/settings`, 'POST', settings)
    context.commit('setGuildSettings', { settings })
  },
  async loadSettings(context) {
    if (!context.state.isLoading) {
      return
    }

    const guildId = context.rootState.guild.activeGuildId
    if (guildId === 'guest') {
      context.commit('setGuestSettings')
    } else {
      context.commit({
        type: 'setGuildSettings',
        settings: await API.request(`guild/${guildId}/settings`),
      })
    }
  },
}

export default {
  namespaced: true,
  state,
  mutations,
  actions,
}
