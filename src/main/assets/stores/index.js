import Vuex from 'vuex'
import Vue from 'vue'

import userModule from './user'
import guildModule from './guild'
import uiModule from './ui'

Vue.use(Vuex)

export const store = new Vuex.Store({
  modules: {
    user: userModule,
    guild: guildModule,
    ui: uiModule,
  },
})
