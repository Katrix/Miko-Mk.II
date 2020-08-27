<template>
  <div class="row">
    <div v-for="(permissions, idx) in permissionChunks" :key="`permChunk-${idx}`" class="col-md-4 form">
      <tristate
        v-for="permission in permissions"
        :key="`permission-${permission.apiName}`"
        :value="permissionData[permission.apiName]"
        :display-name="permission.text"
        :name="`vtPermission-${tristateContextName}-${permission.apiName}`"
        @input="permissionData = { ...permissionData, [permission.apiName]: $event }"
      />
    </div>
  </div>
</template>

<script>
import chunk from 'lodash/chunk'

import Tristate from './Tristate'

const allPermissions = [
  { text: 'Create instant invite', apiName: 'create_instant_invite' },
  { text: 'Manage channel', apiName: 'manage_channels' },
  { text: 'Add reactions', apiName: 'add_reactions' },
  { text: 'Read messages', apiName: 'view_channel' },
  { text: 'Send messages', apiName: 'send_messages' },
  { text: 'Send TTS messages', apiName: 'send_tts_messages' },
  { text: 'Manage messages', apiName: 'manage_messages' },
  { text: 'Embed links', apiName: 'embed_links' },
  { text: 'Attach files', apiName: 'attach_files' },
  { text: 'Read message history', apiName: 'read_message_history' },
  { text: 'Mention everyone', apiName: 'mention_everyone' },
  { text: 'Use external emoji', apiName: 'use_external_emojis' },
  { text: 'Manage roles', apiName: 'manage_roles' },
  { text: 'Manage webhooks', apiName: 'manage_webhooks' },
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
          p.apiName,
          arrays.allow.includes(p.apiName) ? true : arrays.deny.includes(p.apiName) ? false : null,
        ])
      )
    },
  },
}
</script>
