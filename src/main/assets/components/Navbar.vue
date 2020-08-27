<template>
  <b-navbar toggleable="lg" type="dark" variant="primary">
    <b-navbar-brand :to="{ name: 'guild_home' }">
      <!--suppress CheckImageSize -->
      <img class="img-circle" width="48px" height="48px" src="../images/logo.png" alt="Miko logo" />
    </b-navbar-brand>

    <b-navbar-toggle target="miko-navbar-collapse" />
    <b-collapse id="miko-navbar-collapse" is-nav>
      <b-navbar-nav>
        <b-nav-item :to="{ name: 'guild_home' }" exact exact-active-class="active">Home</b-nav-item>
        <b-nav-item :to="{ name: 'guild_help' }" active-class="active">Help</b-nav-item>
        <b-nav-item v-if="inVoiceChat" :to="{ name: 'guild_music' }" active-class="active">
          Music
        </b-nav-item>
        <b-nav-item v-if="isAdmin" :to="{ name: 'guild_settings' }" active-class="active">
          Settings
        </b-nav-item>
        <b-nav-item v-if="isAdmin" active-class="active">Log</b-nav-item>
      </b-navbar-nav>

      <b-navbar-nav class="ml-auto">
        <template v-if="!isGuest">
          <b-nav-item :to="{ name: 'select_guild' }">All guilds</b-nav-item>
          <b-nav-item href="/logout">Logout</b-nav-item>
        </template>
        <b-nav-item v-else :to="{ name: 'home' }">Back to Start</b-nav-item>
      </b-navbar-nav>
    </b-collapse>
  </b-navbar>
</template>

<script>
import { mapState } from 'vuex'
import { BNavbar, BNavbarBrand, BNavbarToggle, BCollapse, BNavbarNav, BNavItem } from 'bootstrap-vue'

export default {
  components: {
    BNavbar,
    BNavbarBrand,
    BNavbarToggle,
    BCollapse,
    BNavbarNav,
    BNavItem,
  },
  computed: {
    ...mapState('guild', ['activeGuildId', 'inVoiceChat', 'isAdmin']),
    isGuest() {
      return this.activeGuildId === 'guest'
    },
  },
}
</script>
