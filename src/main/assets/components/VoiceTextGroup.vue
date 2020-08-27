<template>
  <div>
    <div>
      <p>Everyone</p>
      <voice-text-set
        tristate-context-name="everyone"
        :value="value.everyone"
        @input="$emit('input', { ...value, everyone: $event })"
      />
    </div>

    <div>
      <p class="form-inline">
        Users
        <b-input-group>
          <b-form-input type="number" class="ml-2" v-model="newUserSet" style="min-width: 20%; width: auto;" />

          <template #append>
            <button
              class="btn btn-success"
              :disabled="newUserSet === null || isNaN(parseInt(newUserSet))"
              @click="addUserSet"
            >
              <font-awesome-icon :icon="['fas', 'plus']" />
            </button>
          </template>
        </b-input-group>
      </p>

      <div v-for="(userSet, userId) in value.users" :key="'userPerms-' + userId">
        <p>
          {{ userId }}
          <button
            class="btn btn-danger"
            @click="
              () => {
                const toEmit = { ...value }
                delete toEmit.users[userId]
                $emit('input', toEmit)
              }
            "
          >
            <font-awesome-icon :icon="['fas', 'minus']" />
          </button>
        </p>
        <voice-text-set
          :tristate-context-name="`user-${userId}`"
          :value="userSet"
          @input="
            (event) => {
              const toEmit = { ...value }
              toEmit.users[userId] = event
              $emit('input', toEmit)
            }
          "
        />
      </div>
    </div>

    <div>
      <p>
        Roles
        <b-form-select v-model="newRoleValue" :options="roleOptions" style="min-width: 20%; width: auto;" />
        <button class="btn btn-success" :disabled="newRoleValue === null" @click="addRoleValue">
          <font-awesome-icon :icon="['fas', 'plus']" />
        </button>
      </p>

      <div v-for="(roleValue, roleId) in value.roles" :key="'rolePerms-' + roleId">
        <p>
          {{ rolesMap[roleId].name }}
          <button
            class="btn btn-danger"
            @click="
              () => {
                const toEmit = { ...value }
                delete toEmit.roles[roleId]
                $emit('input', toEmit)
              }
            "
          >
            <font-awesome-icon :icon="['fas', 'minus']" />
          </button>
        </p>
        <voice-text-value
          :tristate-context-name="`role-${roleId}`"
          :value="roleValue"
          @input="
            (event) => {
              const toEmit = { ...value }
              toEmit.roles[roleId] = event
              $emit('input', toEmit)
            }
          "
        />
      </div>
    </div>
  </div>
</template>

<script>
import { mapState, mapGetters } from 'vuex'
import { BFormInput, BFormSelect, BInputGroup } from 'bootstrap-vue'

import VoiceTextSet from './VoiceTextSet'
import VoiceTextValue from './VoiceTextValue'

export default {
  components: {
    VoiceTextValue,
    VoiceTextSet,
    BFormInput,
    BFormSelect,
    BInputGroup,
  },
  props: {
    value: {
      type: Object,
      required: true,
    },
  },
  data() {
    return {
      newUserSet: null,
      newRoleValue: null,
    }
  },
  computed: {
    ...mapState('guild', ['roles']),
    ...mapGetters('guild', ['rolesMap']),
    roleOptions() {
      return [
        { value: null, text: 'Select a role' },
        ...[...this.roles]
          .filter((r) => !(r.id in this.value.roles))
          .sort((a, b) => a.position - b.position)
          .map((r) => ({ value: r.id, text: r.name })),
      ]
    },
  },
  methods: {
    addUserSet() {
      this.$emit('input', {
        ...this.value,
        users: {
          ...this.value.users,
          [this.newUserSet]: { inside: { allow: [], deny: [] }, outside: { allow: [], deny: [] } },
        },
      })
      this.newUserSet = null
    },
    addRoleValue() {
      this.$emit('input', {
        ...this.value,
        roles: { ...this.value.roles, [this.newRoleValue]: { allow: [], deny: [] } },
      })
      this.newRoleValue = null
    },
  },
}
</script>
