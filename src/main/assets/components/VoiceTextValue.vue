<template>
  <div class="row">
    <div v-for="(permissions, idx) in permissionChunks" :key="`permChunk-${idx}`" class="col-md-4 form">
      <tristate
        v-for="permission in permissions"
        :key="`permission-${permission.modelKey}`"
        :value="permissionData[permission.modelKey]"
        :display-name="permission.text"
        :name="`vtPermission-${tristateContextName}-${permission.modelKey}`"
        @input="permissionData = { ...permissionData, [permission.modelKey]: $event }"
      />
    </div>
  </div>
</template>

<script>
import chunk from 'lodash/chunk'

import Tristate from './Tristate'

const allPermissions = [
  { text: 'Create instant invite', modelKey: 'createInstantInvite' },
  { text: 'Manage channel', modelKey: 'manageChannel' },
  { text: 'Add reactions', modelKey: 'addReactions' },
  { text: 'Read messages', modelKey: 'readMessages' },
  { text: 'Send messages', modelKey: 'sendMessages' },
  { text: 'Send TTS messages', modelKey: 'sendTTSMessages' },
  { text: 'Manage messages', modelKey: 'manageMessages' },
  { text: 'Embed links', modelKey: 'embedLinks' },
  { text: 'Attach files', modelKey: 'attachFiles' },
  { text: 'Read message history', modelKey: 'readMessageHistory' },
  { text: 'Mention everyone', modelKey: 'mentionEveryone' },
  { text: 'Use external emoji', modelKey: 'useExternalEmoji' },
  { text: 'Manage roles', modelKey: 'manageRoles' },
  { text: 'Manage webhooks', modelKey: 'manageWebhooks' },
]

export default {
  components: {
    Tristate,
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
    permissionChunks() {
      return chunk(allPermissions, allPermissions.length / 2)
    },
    permissionData: {
      get() {
        return this.arrayToObjectVtPermValue(this.value)
      },
      set(newValue) {
        this.$emit('input', this.objectToArrayVtPermValue(newValue))
      },
    },
  },
  methods: {
    objectToArrayVtPermValue(object) {
      return {
        allow: Object.entries(object).flatMap(([key, value]) => (value === true ? [key] : [])),
        deny: Object.entries(object).flatMap(([key, value]) => (value === false ? [key] : [])),
      }
    },
    arrayToObjectVtPermValue(arrays) {
      return Object.fromEntries(
        allPermissions.map((p) => [
          p.modelKey,
          arrays.allow.includes(p.modelKey) ? true : arrays.deny.includes(p.modelKey) ? false : null,
        ])
      )
    },
  },
}
</script>
