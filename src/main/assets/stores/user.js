import Vue from 'vue'
import { API } from '../api'

const state = {
  availableGuilds: [
    {
      id: '201938197171798017',
      name: 'Yukkuricraft',
      icon: 'e00bd387745c3eca30f6d589c7c907d5',
    },
  ],
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
