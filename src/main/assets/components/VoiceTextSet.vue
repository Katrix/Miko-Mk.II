<template>
  <div class="row">
    <div v-for="context in permissionContexts" :key="'PermContext' + context.prop" class="col-md-4">
      <p>{{ context.text }}</p>
      <voice-text-value
        :tristate-context-name="`${tristateContextName}-${context.prop}`"
        :value="value[context.prop]"
        @input="$emit('input', { ...value, [context.prop]: $event })"
      />
    </div>
  </div>
</template>

<script>
import VoiceTextValue from './VoiceTextValue'

export default {
  components: {
    VoiceTextValue,
  },
  props: {
    value: {
      type: Object,
      required: true,
    },
    tristateContextName: {
      type: String,
      required: true,
    },
  },
  computed: {
    permissionContexts() {
      return [
        { text: 'Inside', prop: 'inside' },
        { text: 'Outside', prop: 'outside' },
      ]
    },
  },
}
</script>
