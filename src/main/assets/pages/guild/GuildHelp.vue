<template>
  <div class="w-100 row">
    <aside class="col-md-2 aside-menu" style="padding: 1rem 2rem;">
      <p class="aside-menu-label">Commands</p>
      <b-nav class="aside-menu-list" vertical pills>
        <li v-for="category in categories" :key="'menu-' + category.name" class="nav-item">
          <a class="nav-link" :class="[active === category.name && 'active']" :href="'#cmd-' + category.name">
            {{ category.name }}
          </a>
        </li>
      </b-nav>
    </aside>
    <div class="col-md-10">
      <div class="">
        <h1>Command help</h1>
        <template v-for="category in categories">
          <template v-if="category.commands && category.commands.length">
            <h2 :id="'cmd-' + category.name" :key="'header-' + category.name">{{ category.name }}</h2>
            <table :key="'commands-' + category.name" class="table table-hover">
              <thead>
                <tr>
                  <th style="width: 8%;">Name</th>
                  <th style="width: 12%;">Aliases</th>
                  <th style="width: 16%;">Usage</th>
                  <th>Description</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="command in category.commands" :key="command.name">
                  <th>{{ command.name }}</th>
                  <th>{{ command.aliases.join('|') }}</th>
                  <th>{{ command.usage }}</th>
                  <th>{{ command.description }}</th>
                </tr>
              </tbody>
            </table>
          </template>
        </template>
      </div>
    </div>
  </div>
</template>

<script>
import { mapState } from 'vuex'
import { BNav } from 'bootstrap-vue'

export default {
  components: {
    BNav,
  },
  computed: {
    active() {
      return this.$route.hash.substring('#cmd'.length)
    },
    ...mapState('guild/commands', ['categories']),
  },
  watch: {
    categories: {
      immediate: true,
      handler() {
        if (!location.hash && Object.keys(this.categories).length) {
          location.hash = '#cmd' + Object.keys(this.categories)[0]
        }
      },
    },
  },
  beforeCreate() {
    this.$store.dispatch('guild/commands/loadData')
  },
}
</script>
