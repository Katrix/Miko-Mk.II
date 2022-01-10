import Vue from 'vue'
import { API } from '../api'
import musicModule from './music'
import settingsModule from './settings'
import commandsModule from './commands'

const guestState = {
  activeGuildId: 'guest',
  inVoiceChat: true,
  isAdmin: true,
  textChannels: [
    {
      id: '112345',
      name: 'foo-text',
      position: 1,
    },
    {
      id: '112346',
      name: 'bar-text',
      position: 2,
    },
  ],
  voiceChannels: [
    {
      id: '212345',
      name: 'Foo voice',
      position: 3,
    },
    {
      id: '212346',
      name: 'Bar voice',
      position: 4,
    },
  ],
  categories: [
    {
      id: '312345',
      name: 'Foo category',
      position: 5,
    },
    {
      id: '312346',
      name: 'Bar category',
      position: 6,
    },
  ],
  roles: [
    {
      id: '412345',
      name: 'Admin',
      position: 0,
    },
    {
      id: '412346',
      name: 'User',
      position: 5,
    },
  ]
}

const state = {
  ...guestState,
}

const getters = {
  textChannelsMap(state) {
    return Object.fromEntries(state.textChannels.map((channel) => [channel.id, channel]))
  },
  voiceChannelsMap(state) {
    return Object.fromEntries(state.voiceChannels.map((channel) => [channel.id, channel]))
  },
  categoriesMap(state) {
    return Object.fromEntries(state.categories.map((channel) => [channel.id, channel]))
  },
  rolesMap(state) {
    return Object.fromEntries(state.roles.map((role) => [role.id, role]))
  },
}

const mutations = {
  setActiveGuild(state, payload) {
    state.activeGuildId = payload.guildId
    state.inVoiceChat = false
    state.isAdmin = false
    Vue.set(state, 'textChannels', [])
    Vue.set(state, 'voiceChannels', [])
    Vue.set(state, 'categories', [])
    Vue.set(state, 'roles', [])
    Vue.set(state, 'commandData', { categories: {} })
  },
  setGuestData(state) {
    state.inVoiceChat = true
    state.isAdmin = true

    Vue.set(state, 'textChannels', [...guestState.textChannels])
    Vue.set(state, 'voiceChannels', [...guestState.voiceChannels])
    Vue.set(state, 'categories', [...guestState.categories])
    Vue.set(state, 'roles', [...guestState.roles])
  },
  setGuildData(state, { data }) {
    state.inVoiceChat = data.inVoiceChat
    state.isAdmin = data.isAdmin
    Vue.set(state, 'textChannels', data.textChannels)
    Vue.set(state, 'voiceChannels', data.voiceChannels)
    Vue.set(state, 'categories', data.categories)
    Vue.set(state, 'roles', data.roles)
  },
}

const actions = {
  async getGuildData(context) {
    if (context.state.activeGuildId === 'guest') {
      context.commit('setGuestData')
      return
    }

    context.commit({
      type: 'setGuildData',
      data: await API.request(`guild/${context.state.activeGuildId}/info`),
    })
  },
  switchActiveGuild(context, payload) {
    context.commit('setActiveGuild', payload)
    context.dispatch('music/resetConnection')
    context.commit('commands/clearData')
    context.commit('settings/clearSettings')
    context.dispatch('getGuildData')
  },
}

export default {
  namespaced: true,
  state,
  getters,
  mutations,
  actions,
  modules: {
    music: musicModule,
    settings: settingsModule,
    commands: commandsModule,
  },
}
