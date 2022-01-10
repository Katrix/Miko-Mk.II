<template>
  <div class="container" style="height: 100vh;">
    <div class="row align-items-center text-center justify-content-center h-100">
      <div class="col-md-8">
        <div class="card">
          <div class="card-header">
            <h1>Miko Web Interface</h1>
          </div>
          <div class="card-body">
            <div class="row">
              <div class="col">
                <h2 class="h5">Login and start using the web interface</h2>
                <a class="btn btn-primary" href="/codegrant">Login</a>
              </div>
              <div class="col">
                <h2 class="h5">Invite Miko to a new guild</h2>
                <a
                  class="btn btn-primary"
                  :href="`https://discordapp.com/api/oauth2/authorize?client_id=${config.clientId}&scope=bot%20applications.commands&permissions=335670360`"
                >
                  Invite Miko
                </a>
              </div>
              <div class="col">
                <h2 class="h5">Try the web interface</h2>
                <router-link class="btn btn-primary" :to="{ name: 'guest_mode' }">Guest mode</router-link>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import config from '../config'

export default {
  computed: {
    config() {
      return config
    },
  },
}
</script>
