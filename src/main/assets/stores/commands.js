import Vue from 'vue'
import { API } from '../api'

const guestState = {
  isLoading: false,
  categories: [
    {
      name: 'General',
      prefixes: ['m!'],
      commands: [
        {
          name: 'Ping',
          aliases: ['ping'],
          usage: '',
          description: 'Check if the bot is alive',
        },
      ],
    },
    {
      name: 'Voice',
      prefixes: ['mm!'],
      commands: [
        {
          name: 'Queue',
          aliases: ['q', 'queue'],
          usage: '<url to add>',
          description: 'Adds a new track to the music playlist',
        },
      ],
    },
  ],
}

const state = {
  ...guestState,
}

const mutations = {
  clearData(state) {
    state.categories = []
    state.isLoading = true
  },
  setData(state, { data }) {
    Vue.set(state, 'categories', data.categories)
    state.isLoading = false
  },
  setGuestData(state) {
    Vue.set(state, 'categories', guestState.categories)
    state.isLoading = false
  },
}

const actions = {
  async loadData(context) {
    if (!context.state.isLoading) {
      return
    }

    const guildId = context.rootState.guild.activeGuildId
    if (guildId === 'guest') {
      context.commit('setGuestData')
    } else {
      context.commit({
        type: 'setData',
        data: await API.request(`guild/${guildId}/commands`),
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
