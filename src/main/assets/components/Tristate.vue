<template>
  <b-form-group :label="displayName + ':'">
    <div class="tristate-wrapper">
      <label class="tristate-no" :for="'tristate_' + name + '_no'" aria-label="No">X</label>
      <input
        :id="'tristate_' + name + '_no'"
        v-model="radioValue"
        class="tristate-no"
        type="radio"
        :name="name"
        value="no"
      />

      <label class="tristate-neutral" :for="'tristate_' + name + '_neutral'" aria-label="Neutral">/</label>
      <input
        :id="'tristate_' + name + '_neutral'"
        v-model="radioValue"
        class="tristate-neutral"
        type="radio"
        :name="name"
        value="neutral"
      />

      <label class="tristate-yes" :for="'tristate_' + name + '_yes'" aria-label="Yes">V</label>
      <input
        :id="'tristate_' + name + '_yes'"
        v-model="radioValue"
        class="tristate-yes"
        type="radio"
        :name="name"
        value="yes"
      />
      <span class="tristate-toggle"></span>
    </div>
  </b-form-group>
</template>

<script>
import { BFormGroup } from 'bootstrap-vue'

export default {
  components: {
    BFormGroup,
  },
  props: {
    displayName: {
      type: String,
      required: true,
    },
    name: {
      type: String,
      required: true,
    },
    value: Boolean,
    errors: Array,
  },
  computed: {
    radioValue: {
      get() {
        if (this.value === true) {
          return 'yes'
        } else if (this.value === false) {
          return 'no'
        } else if (this.value === null) {
          return 'neutral'
        }

        throw new Error(`Unexpected value`)
      },
      set(newValue) {
        let toEmit
        if (newValue === 'yes') {
          toEmit = true
        } else if (newValue === 'no') {
          toEmit = false
        } else if (newValue === 'neutral') {
          toEmit = null
        }

        this.$emit('input', toEmit)
      },
    },
  },
}
</script>
