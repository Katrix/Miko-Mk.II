import Vue from 'vue'
import { API } from '../api'

const state = {
  availableGuilds: [],
}

const mutations = {
  setAvailableGuilds(state, payload) {
    Vue.set(state, 'availableGuilds', payload.guilds)
  },
}

const actions = {
  async getAvailableGuilds(context) {
    context.commit({
      type: 'setAvailableGuilds',
      ...(await API.request('guilds')),
    })
  },
}

export default {
  namespaced: true,
  state,
  mutations,
  actions,
}
