<template>
  <div class="container" style="height: 100vh;">
    <div class="row align-items-center text-center justify-content-center h-100">
      <div class="col-md-8">
        <div class="card">
          <div class="card-header">
            <h1>Select a guild</h1>
          </div>
          <div class="card-body">
            <template v-if="availableGuilds.length > 0">
              <div class="scrolling-wrapper-x">
                <figure
                    v-for="guild in availableGuilds.filter((g) => g.name.toLowerCase().includes(filter.toLowerCase()))"
                    :key="guild.id"
                    class="figure scroll-card mr-3"
                    style="width: 128px;"
                >
                  <router-link :to="{ name: 'guild_home', params: { guild: guild.id } }" v-slot="{ href, navigate }">
                    <a :href="href" @click="navigate">
                      <img
                          class="figure-img img-circle"
                          width="128px"
                          height="128px"
                          :src="guildImgSrc(guild)"
                          :alt="guild.name"
                      />
                    </a>
                  </router-link>
                  <figcaption class="figure-caption text-center">{{ guild.name }}</figcaption>
                </figure>
              </div>
              <div class="form-group">
                <label class="sr-only" for="searchInput">Search...</label>
                <input class="form-control" id="searchInput" type="text" v-model="filter" placeholder="Search..." />
              </div>
            </template>
            <div v-else>
              No guilds available.

              <h2 class="h5">Invite Miko to a new guild</h2>
              <a
                  class="btn btn-primary"
                  :href="`https://discordapp.com/api/oauth2/authorize?client_id=${config.clientId}&scope=bot%20applications.commands&permissions=335670360`"
              >
                Invite Miko
              </a>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { mapState } from 'vuex'
import config from "../config"

export default {
  data() {
    return {
      filter: '',
    }
  },
  computed: {
    ...mapState('user', ['availableGuilds']),
    config() {
      return config
    }
  },
  methods: {
    guildImgSrc(guild) {
      return (
        (guild.icon && `https://cdn.discordapp.com/icons/${guild.id}/${guild.icon}.png?size=128`) ??
        `https://cdn.discordapp.com/embed/avatars/${Math.floor(Math.random() * 5)}.png?size=128`
      )
    },
  },
  async beforeCreate() {
    await this.$store.dispatch('user/getAvailableGuilds')
  }
}
</script>
