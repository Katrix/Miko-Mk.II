<template>
  <div class="row w-100">
    <aside class="col-md-2 aside-menu" style="padding: 1rem 2rem;">
      <p class="aside-menu-label">General</p>
      <b-nav class="aside-menu-list" vertical pills>
        <li class="nav-item">
          <a class="nav-link" :class="[active === 'general' && 'active']" href="#setting-general">General</a>
          <a class="nav-link" :class="[active === 'modLog' && 'active']" href="#setting-mogLog">ModLog</a>
          <a class="nav-link" :class="[active === 'voiceText' && 'active']" href="#setting-voiceText">VoiceText</a>
          <a class="nav-link" :class="[active === 'commands' && 'active']" href="#setting-commands">General</a>
        </li>
      </b-nav>

      <p class="aside-menu-label">Fun</p>
      <b-nav class="aside-menu-list" vertical pills>
        <li class="nav-item">
          <a class="nav-link" :class="[active === 'todo' && 'active']" href="#setting-todo">TODO</a>
        </li>
      </b-nav>
    </aside>
    <div class="col">
      <div class="form">
        <h1>Settings</h1>

        <h2>General settings</h2>
        <h3 id="setting-general">General</h3>
        <b-form-group
          label-for="botSpamSelect"
          label="Bot spam channel"
          description="A channel where Miko will dump lots of info if no other channel to dump it in is found"
        >
          <b-form-select
            id="botSpamSelect"
            v-model="newSettings.channels.botSpamChannel"
            style="min-width: 20%; width: auto;"
            :options="textChannelOptions"
          />
        </b-form-group>

        <b-form-group
          label-for="staffChatSelect"
          label="Staff channel"
          description="Like bot spam channel, but much less spam, and more secretive messages"
        >
          <b-form-select
            id="staffChatSelect"
            v-model="newSettings.channels.staffChannel"
            style="min-width: 20%; width: auto;"
            :options="textChannelOptions"
          />
        </b-form-group>

        <h3 id="setting-modLog">ModLog</h3>

        <b-form-group
            label-for="modLogChannelSelect"
            label="Mod log channel"
            description="A channel where Miko will drop changes to things in the server"
        >
          <b-form-select
              id="modLogChannelSelect"
              v-model="newSettings.modLog.channelId"
              style="min-width: 20%; width: auto;"
              :options="textChannelOptions"
          />
        </b-form-group>

        <b-form-group label="Ignored Audit log events" v-slot="{ariaDescribedBy}">
          <b-form-checkbox-group
              id="modLogIgnoredAuditLogEvents"
              v-model="newSettings.modLog.ignoredAuditLogEvents"
              :options="auditLogEventOptions"
              :aria-describedby="ariaDescribedBy" />
        </b-form-group>


        <h3 id="setting-voiceText">VoiceText</h3>
        <b-form-checkbox id="vtEnabled" v-model="newSettings.voiceText.enabled">
          VoiceText Enabled
        </b-form-checkbox>

        <b-form-group
          label-for="vtDynamicallyResizeChannels"
          label="Dynamically resize channels limit (0 for disabled)"
          description="Creates and destroys voice channels as people join them"
        >
          <b-form-input
            id="vtDynamicallyResizeChannels"
            v-model="newSettings.voiceText.dynamicallyResizeChannels"
            style="min-width: 20%; width: auto;"
            type="number"
            number
          ></b-form-input>
        </b-form-group>

        <b-form-group
          label-for="vtBlacklistChannels"
          label="Blacklist channels for VoiceText"
          description="Channels to exempt from creating a complementary text channel for"
        >
          <b-form-select
            v-model="newSettings.voiceText.blacklist.channels"
            multiple
            style="min-width: 20%; width: auto;"
            :options="voiceChannelOptions"
          />
        </b-form-group>

        <b-form-group
          label-for="vtBlacklistCategories"
          label="Blacklist categories for VoiceText"
          description="Categories to exempt from creating a complementary text channel for"
        >
          <b-form-select
            v-model="newSettings.voiceText.blacklist.categories"
            multiple
            style="min-width: 20%; width: auto;"
            :options="categoriesOptions"
          />
        </b-form-group>

        <h4>Destructive settings</h4>
        <b-form-checkbox id="vtDestructiveEnabled" v-model="newSettings.voiceText.destructive.enabled" inline>
          Destructive Enabled
        </b-form-checkbox>
        <b-form-checkbox
          id="vtDestructiveSaveOnDestroy"
          v-model="newSettings.voiceText.destructive.saveDestroyed"
          inline
        >
          Save on destroy (Make sure a secret key has been generated first)
        </b-form-checkbox>

        <b-form-group
          label-for="vtDestructiveBlacklist"
          label="Destructive blacklist"
          description="Channels to exempt from channel deletion"
        >
          <b-form-select
            v-model="newSettings.voiceText.destructive.blacklist"
            multiple
            style="min-width: 20%; width: auto;"
            :options="textChannelVoiceOptions"
          />
        </b-form-group>

        <h4>VoiceText permission</h4>
        <b-form-group>
          <h5>Global</h5>
          <voice-text-group v-model="newSettings.voiceText.perms.global"></voice-text-group>
        </b-form-group>

        <b-form-group>
          <h5>
            Channel overrides
            <b-form-select
              v-model="newChannelOverrideId"
              style="min-width: 20%; width: auto;"
              :options="channelOverridesOptions"
            />
            <button class="btn btn-success" :disabled="newChannelOverrideId === null" @click="addChannelOverride">
              <font-awesome-icon :icon="['fas', 'plus']" />
            </button>
          </h5>
          <template v-for="(_, voiceChannelId) in newSettings.voiceText.perms.overrideChannel">
            <h6 :key="'overrideChannelHeader' + voiceChannelId">
              {{ voiceChannelsMap[voiceChannelId].name }}
              <button
                class="btn btn-danger"
                @click="$delete(newSettings.voiceText.perms.overrideChannel, voiceChannelId)"
              >
                <font-awesome-icon :icon="['fas', 'minus']" />
              </button>
            </h6>
            <voice-text-group
              :key="'overrideChannelGroup' + voiceChannelId"
              v-model="newSettings.voiceText.perms.overrideChannel[voiceChannelId]"
            />
          </template>
        </b-form-group>

        <b-form-group>
          <h5>
            Category overrides
            <b-form-select
              v-model="newCategoryOverrideId"
              style="min-width: 20%; width: auto;"
              :options="categoryOverridesOptions"
            />
            <button class="btn btn-success" :disabled="newCategoryOverrideId === null" @click="addCategoryOverride">
              <font-awesome-icon :icon="['fas', 'plus']" />
            </button>
          </h5>
          <template v-for="(_, categoryId) in newSettings.voiceText.perms.overrideCategory">
            <h6 :key="'overrideCategoryHeader' + categoryId">
              {{ categoriesMap[categoryId].name }}
              <button class="btn btn-danger" @click="$delete(newSettings.voiceText.perms.overrideCategory, categoryId)">
                <font-awesome-icon :icon="['fas', 'minus']" />
              </button>
            </h6>
            <voice-text-group
              :key="'overrideCategoryGroup' + categoryId"
              v-model="newSettings.voiceText.perms.overrideCategory[categoryId]"
            />
          </template>
        </b-form-group>

        <h3 id="setting-commands">Commands</h3>
        <h4>Comming soon</h4>

        <h2>Fun</h2>
        <h3 id="setting-todo">Fun</h3>
        <h4>Comming soon</h4>

        <button v-if="$route.params.guild !== 'guest'" class="btn btn-primary" @click="submit">Submit</button>
      </div>
    </div>
  </div>
</template>

<script>
import { mapState, mapGetters } from 'vuex'
import { BNav, BFormGroup, BFormSelect, BFormCheckbox, BFormCheckboxGroup, BFormInput } from 'bootstrap-vue'
import VoiceTextGroup from '../../components/VoiceTextGroup'

export default {
  components: {
    VoiceTextGroup,
    BNav,
    BFormGroup,
    BFormSelect,
    BFormCheckbox,
    BFormCheckboxGroup,
    BFormInput,
  },
  data() {
    return {
      newSettings: {
        channels: {
          botSpamChannel: null,
          staffChannel: null,
        },
        music: {
          defaultMusicVolume: 100,
        },
        voiceText: {
          enabled: false,
          blacklist: {
            channels: [],
            categories: [],
          },
          dynamicallyResizeChannels: 0,
          destructive: {
            enabled: false,
            saveDestroyed: false,
            blacklist: [],
          },
          perms: {
            global: this.makeNewPermsGroup(),
            overrideChannel: {},
            overrideCategory: {},
          },
        },
        commands: {
          requiresMention: false,
          prefixes: {
            general: ['m!'],
            music: ['mm!'],
          },
          permissions: {
            general: {
              categoryWide: 'disallow',
              categoryMergeOperation: 'or',
              cleanup: 'allow',
              shiftChannels: 'allow',
              info: 'allow',
              safebooru: 'allow',
            },
            music: {
              categoryWide: 'disallow',
              categoryMergeOperation: 'or',
              pause: 'allow',
              volume: 'allow',
              defVolume: 'allow',
              stop: 'allow',
              nowPlaying: 'allow',
              queue: 'allow',
              next: 'allow',
              prev: 'allow',
              clear: 'allow',
              shuffle: 'allow',
              ytQueue: 'allow',
              scQueue: 'allow',
              gui: 'allow',
              seek: 'allow',
              progress: 'allow',
              loop: 'allow',
            },
          },
        },
        modLog: {
          channelId: null,
          ignoredAuditLogEvents: [],
          ignoredChannels: [],
          ignoredUsers: []
        }
      },
      newChannelOverrideId: null,
      newCategoryOverrideId: null,
    }
  },
  computed: {
    ...mapState('guild', ['settings', 'textChannels', 'voiceChannels', 'categories']),
    ...mapGetters('guild', ['voiceChannelsMap', 'categoriesMap']),
    active() {
      return this.$route.hash.substring('#setting-'.length)
    },
    textChannelOptions() {
      return [{ value: null, text: 'None' }].concat(this.makeOptions(this.textChannels))
    },
    voiceChannelOptions() {
      return this.makeOptions(this.voiceChannels)
    },
    categoriesOptions() {
      return this.makeOptions(this.categories)
    },
    textChannelVoiceOptions() {
      return this.makeOptions(this.textChannels).filter(({ text }) => text.endsWith('-voice'))
    },
    channelOverridesOptions() {
      return [{ value: null, text: 'Select a channel' }].concat(
        this.makeOptions(this.voiceChannels.filter((c) => !(c.id in this.newSettings.voiceText.perms.overrideChannel)))
      )
    },
    categoryOverridesOptions() {
      return [{ value: null, text: 'Select a category' }].concat(
        this.makeOptions(this.categories.filter((c) => !(c.id in this.newSettings.voiceText.perms.overrideCategory)))
      )
    },
    auditLogEventOptions() {
      return [
        "GuildUpdate",
        "ChannelCreate",
        "ChannelUpdate",
        "ChannelDelete",
        "ChannelOverwriteCreate",
        "ChannelOverwriteUpdate",
        "ChannelOverwriteDelete",
        "MemberKick",
        "MemberPrune",
        "MemberBanAdd",
        "MemberBanRemove",
        "MemberUpdate",
        "MemberRoleUpdate",
        "MemberMove",
        "MemberDisconnect",
        "BotAdd",
        "RoleCreate",
        "RoleUpdate",
        "RoleDelete",
        "InviteCreate",
        "InviteUpdate",
        "InviteDelete",
        "WebhookCreate",
        "WebhookUpdate",
        "WebhookDelete",
        "EmojiCreate",
        "EmojiUpdate",
        "EmojiDelete",
        "MessageDelete",
        "MessageBulkDelete",
        "MessagePin",
        "MessageUnpin",
        "IntegrationCreate",
        "IntegrationUpdate",
        "IntegrationDelete",
        "StageInstanceCreate",
        "StageInstanceUpdate",
        "StageInstanceDelete",
        "StickerCreate",
        "StickerUpdate",
        "StickerDelete",
        "GuildScheduledEventCreate",
        "GuildScheduledEventUpdate",
        "GuildScheduledEventDelete",
        "ThreadCreate",
        "ThreadUpdate",
        "ThreadDelete",
        "Unknown"
      ]
    }
  },
  watch: {
    settings: {
      immediate: true,
      handler(val) {
        //TODO: Updating stuff here breaks stuff
        this.$set(this, 'newSettings', val)
      },
    },
  },
  beforeCreate() {
    this.$store.dispatch('guild/settings/loadSettings')
  },
  methods: {
    makeOptions(channels) {
      return [...channels].sort((a, b) => a.position - b.position).map((c) => ({ value: c.id, text: c.name }))
    },
    submit() {
      //TODO: Expose in the UI that updates have been applied
      this.$store.dispatch({
        type: 'guild/settings/updateSettings',
        settings: this.newSettings,
      })
    },
    makeNewPermsGroup() {
      return {
        everyone: {
          inside: {
            allow: [],
            deny: [],
          },
          outside: {
            allow: [],
            deny: [],
          },
        },
        users: {},
        roles: {},
      }
    },
    addChannelOverride() {
      this.$set(this.newSettings.voiceText.perms.overrideChannel, this.newChannelOverrideId, this.makeNewPermsGroup())
      this.newChannelOverrideId = null
    },
    addCategoryOverride() {
      this.$set(this.newSettings.voiceText.perms.overrideCategory, this.newCategoryOverrideId, this.makeNewPermsGroup())
      this.newCategoryOverrideId = null
    },
  },
}
</script>
