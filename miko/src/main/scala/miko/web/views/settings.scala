package miko.web.views

import ackcord.data.{ChannelId, Permission, TGuildChannel}
import miko.settings.{GuildSettings, VTPermissionValue}
import miko.web.forms.WebSettings
import miko.web.layouts.MikoBundle._
import play.api.data.Form
import play.api.i18n.Messages
import play.twirl.api.Html

object settings {

  private def menuCat(label: String, items: Seq[Frag]) =
    frag(
      <.p(^.cls := "menu-label", label),
      <.ul(^.cls := "menu-list")(
        items.map(item => <.li(item))
      )
    )

  private def isActive(id: String) = id == "general"

  private def menuItem(name: String, idFor: String) =
    <.a((^.cls := "is-active").when(isActive(idFor)), ^.href := s"#$idFor", name)

  def apply(
      guildSettings: GuildSettings,
      form: Form[WebSettings]
  )(implicit info: GuildViewInfo, header: RequestHeader, messages: Messages): String = {
    val vtSettings = guildSettings.vtSettings
    val tChannels = info.guild.channels.collect {
      case (k, ch: TGuildChannel) => k -> ch
    }

    def showErrors(key: String) =
      form.errors(key).map { error =>
        <.p(^.cls := "help is-danger", error.format)
      }

    def tChannelSelection(current: Option[ChannelId], id: String, name: String) =
      control(
        <.div(^.cls := "select")(
          <.select(^.id := id, ^.name := name)(
            <.option("None"),
            tChannels.toSeq
              .sortBy(_._2.position)
              .map(t => t._1 -> t._2.name)
              .map { case (id, name) => <.option(^.value := id.toLong, ^.selected.when(current.contains(id)), name) }
          )
        )
      )

    def tristate(name: String, displayName: String, permission: Permission, value: VTPermissionValue) =
      frag(
        <.label(s"$displayName:"),
        <.div(^.cls := "tristate-wrapper")(
          <.label(^.cls := "tristate-no", ^.`for` := s"${name}_no", ^.aria.label := "No", "X"),
          <.input(
            ^.cls := "tristate-no",
            ^.tpe := "radio",
            ^.name := name,
            ^.id := s"${name}_no",
            ^.value := "off",
            ^.checked.when(value.deny.hasPermissions(permission))
          ),
          <.label(^.cls := "tristate-neutral", ^.`for` := s"${name}_neutral", ^.aria.label := "Neutral", "/"),
          <.input(
            ^.tpe := "radio",
            ^.name := name,
            ^.id := s"${name}_neutral",
            ^.value := "undefined",
            ^.checked.when(!value.allow.hasPermissions(permission) && !value.deny.hasPermissions(permission))
          ),
          <.label(^.cls := "tristate-yes", ^.`for` := s"${name}_yes", ^.aria.label := "Yes", "V"),
          <.input(
            ^.cls := "tristate-yes",
            ^.tpe := "radio",
            ^.name := name,
            ^.id := s"${name}_yes",
            ^.value := "on",
            ^.checked.when(value.allow.hasPermissions(permission))
          ),
          <.span(^.cls := "tristate-toggle")
        ),
        showErrors(name)
      )

    def tChannelPermissionSection(displayName: String, name: String, value: VTPermissionValue) = {
      frag(
        <.h5(^.cls := "subtitle", is._5, displayName),
        columns(
          column(
            tristate(s"$name.createInstantInvite", "Create instant invite", Permission.CreateInstantInvite, value),
            tristate(s"$name.manageChannel", "Manage channel", Permission.ManageChannels, value),
            tristate(s"$name.addReactions", "Add reactions", Permission.AddReactions, value),
            tristate(s"$name.readMessages", "Read messages", Permission.ViewChannel, value),
            tristate(s"$name.sendMessages", "Send messages", Permission.SendMessages, value),
            tristate(s"$name.sendTtsMessages", "Send TTS messages", Permission.SendTtsMessages, value),
            tristate(s"$name.manageMessages", "Manage messages", Permission.ManageMessages, value)
          ),
          column(
            tristate(s"$name.embedLinks", "Embed links", Permission.EmbedLinks, value),
            tristate(s"$name.attachFiles", "Attach files", Permission.AttachFiles, value),
            tristate(s"$name.readMessageHistory", "Read message history", Permission.ReadMessageHistory, value),
            tristate(s"$name.mentionEveryone", "Mention everyone", Permission.MentionEveryone, value),
            tristate(s"$name.useExternalEmojis", "Use external emoji", Permission.UseExternalEmojis, value),
            tristate(s"$name.manageRoles", "Manage roles", Permission.ManageRoles, value),
            tristate(s"$name.manageWebhooks", "Manage webhooks", Permission.ManageWebhooks, value)
          )
        )
      )
    }

    val formContent = column(
      <.h2(^.cls := "title", is._2, "General settings"),
      <.h3(^.cls := "subtitle", is._3, ^.id := "general", "General"),
      field(
        label(forA = "botSpamSelect", "Bot spam channel"),
        tChannelSelection(guildSettings.botSpamChannel, "botSpamSelect", "botSpam"),
        <.p(^.cls := "help", "A channel where Miko will dump lots of info if no other channel to dump it in is found"),
        showErrors("botSpam")
      ),
      field(
        label(forA = "staffChatSelect", "Staff channel"),
        tChannelSelection(guildSettings.staffChannel, "staffChatSelect", "staffChat"),
        <.p(^.cls := "help", "Like bot spam channel, but much less spam, and more secretive messages"),
        showErrors("staffChat")
      ),
      <.h3(^.cls := "subtitle", is._3, ^.id := "voiceText", "VoiceText"),
      field(is.grouped)(
        control(
          checkbox("vtEnabled", " VoiceText Enabled", "vt.enabled", vtSettings.enabled),
          showErrors("vt.enabled")
        ),
        control(
          labelWithInput(
            " Dynamically resize channels limit (0 for disabled)",
            <.input(
              ^.tpe := "number",
              ^.name := "vt.dynamicallyResizeChannels",
              ^.value := vtSettings.dynamicallyResizeChannels
            )
          ),
          showErrors("vt.dynamicallyResizeChannels")
        )
      ),
      <.h4(^.cls := "subtitle", is._4, "Destructive settings"),
      field(is.grouped)(
        control(
          checkbox(
            "vtDestructiveEnabled",
            " Destructive Enabled",
            "vt.destructive.enabled",
            vtSettings.destructiveEnabled
          ),
          showErrors("vt.destructive.enabled")
        ),
        control(
          checkbox(
            "vtDestructiveSaveOnDestroy",
            " Save on destroy (Make sure a secret key has been generated first)",
            "vt.destructive.saveOnDestroy",
            vtSettings.saveDestructable
          ),
          showErrors("vt.destructive.saveOnDestroy")
        )
      ),
      field(
        label(forA = "vtDestructiveBlacklist", "Destructive blacklist"),
        control(
          <.div(^.cls := "select", is.multiple)(
            <.select(^.id := "vtDestructiveBlacklist", ^.name := "vt.destructive.blacklist[]", ^.multiple, ^.size := 5)(
              tChannels.toSeq
                .sortBy(_._2.position)
                .collect {
                  case (id, chan) if chan.name.endsWith("-voice") =>
                    <.option(
                      ^.value := id.toLong,
                      ^.selected.when(vtSettings.destructiveBlacklist.contains(id)),
                      chan.name
                    )
                }
            )
          )
        ),
        showErrors("vt.destructive.blacklist[]")
      ),
      <.h4(^.cls := "subtitle", is._4, "VoiceText permission"),
      columns(
        column(^.cls := "box", tChannelPermissionSection("Everyone", "vt.perms.everyone", vtSettings.permsEveryone)),
        column(^.cls := "box", tChannelPermissionSection("Join", "vt.perms.join", vtSettings.permsJoin)),
        column(^.cls := "box", tChannelPermissionSection("Leave", "vt.perms.leave", vtSettings.permsLeave))
      ),
      <.h3(^.cls := "subtitle", is._3, ^.id := "commands", "Commands"),
      <.h4(^.cls := "subtitle", is._5, "Coming soon"),
      <.h2(^.cls := "title", is._2, "Fun stuff"),
      <.h3(^.cls := "subtitle", is._3, ^.id := "todo", "Coming soon")
    )

    miko.web.layouts.main("Settings - Miko")(
      columns(
        column(is._2)(
          <.aside(^.cls := "menu")(
            menuCat(
              "General",
              Seq(
                menuItem("General", "general"),
                menuItem("VoiceText", "voiceText"),
                menuItem("Commands", "commands")
              )
            ),
            menuCat(
              "Fun",
              Seq(
                menuItem("TODO", "todo")
              )
            )
          )
        ),
        if (info.isGuest) {
          formContent
        } else {
          raw(
            views.html.helper
              .form(
                views.html.helper.CSRF(miko.web.controllers.routes.WebController.submitSettings(info.guildId.asString))
              )(
                Html(
                  frag(
                    formContent,
                    <.input(^.cls := "button", ^.tpe := "submit", ^.value := "Submit settings")
                  ).render
                )
              )
              .body
          )
        }
      )
    )
  }
}
