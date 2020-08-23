<template>
  <div :style="topStyle">
    <nav-bar />
    <router-view />
  </div>
</template>

<script>
import { mapState } from 'vuex'
import NavBar from '../../components/Navbar'

export default {
  components: {
    NavBar,
  },
  props: {
    guild: {
      type: String,
      required: true,
    },
  },
  computed: {
    ...mapState('ui', ['topStyle']),
  },
  watch: {
    guild: {
      immediate: true,
      handler(val) {
        if (val) {
          this.$store.dispatch({
            type: 'guild/switchActiveGuild',
            guildId: val,
          })
        }
      },
    },
  },
}
</script>
