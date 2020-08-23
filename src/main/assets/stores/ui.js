const state = {
  topStyle: null,
}

const mutations = {
  setTopStyle(state, payload) {
    state.topStyle = payload.style
  },
  clearTopStyle(state) {
    state.topStyle = null
  },
}

export default {
  namespaced: true,
  state,
  mutations,
}
