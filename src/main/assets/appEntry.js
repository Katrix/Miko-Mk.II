import 'regenerator-runtime/runtime.js'

import Vue from 'vue'
import VueRouter from 'vue-router'

import { FontAwesomeIcon } from '@fortawesome/vue-fontawesome'
import './font-awesome'

import './scss/main.scss'
import App from './App'
import { store } from './stores/index'

import SelectGuild from './pages/SelectGuild'
import Login from './pages/Login'
import GuildTop from './pages/guild/GuildTop'
import GuildHome from './pages/guild/GuildHome'
import GuildHelp from './pages/guild/GuildHelp'
import GuildMusic from './pages/guild/GuildMusic'
import GuildSettings from './pages/guild/GuildSettings'

Vue.use(VueRouter)

Vue.component('FontAwesomeIcon', FontAwesomeIcon)
Vue.config.productionTip = false

const router = new VueRouter({
  base: '/',
  mode: 'history',
  routes: [
    {
      path: '/',
      name: 'home',
      component: Login,
    },
    {
      path: '/guilds',
      name: 'select_guild',
      component: SelectGuild,
    },
    {
      path: '/guest-mode',
      name: 'guest_mode',
      redirect: { name: 'guild_home', params: { guild: 'guest' } },
    },
    {
      path: '/guilds/:guild',
      component: GuildTop,
      props: true,
      children: [
        {
          path: '',
          name: 'guild_home',
          component: GuildHome,
        },
        {
          path: 'help',
          name: 'guild_help',
          component: GuildHelp,
        },
        {
          path: 'music',
          name: 'guild_music',
          component: GuildMusic,
        },
        {
          path: 'settings',
          name: 'guild_settings',
          component: GuildSettings,
        },
      ],
    },
  ],
})

if (location.pathname === '/' && typeof isAuthenticated !== 'undefined' && isAuthenticated) {
  router.push({ name: 'select_guild' })
}

// eslint-disable-next-line no-new
new Vue({
  el: '#app',
  render: (createElement) => createElement(App),
  router,
  store,
})
