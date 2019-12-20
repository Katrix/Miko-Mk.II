# --- !Ups

CREATE TYPE VT_PERMISSION_VALUE AS (
    allow BIGINT,
    deny BIGINT
    );

CREATE TABLE guild_settings
(
    guild_id                       BIGINT              NOT NULL PRIMARY KEY,
    bot_spam_channel               BIGINT,
    staff_channel                  BIGINT,
    default_music_volume           SMALLINT                     DEFAULT 100,
    public_key                     BYTEA,
    private_key_channel_id         BIGINT,
    private_key_msg_id             BIGINT,
    requires_mention               BOOLEAN             NOT NULL DEFAULT TRUE,
    vt_enabled                     BOOLEAN             NOT NULL DEFAULT FALSE,
    vt_perms_everyone              VT_PERMISSION_VALUE NOT NULL DEFAULT (0, 0),
    vt_perms_join                  VT_PERMISSION_VALUE NOT NULL DEFAULT (0, 0),
    vt_perms_leave                 VT_PERMISSION_VALUE NOT NULL DEFAULT (0, 0),
    vt_dynamically_resize_channels SMALLINT            NOT NULL DEFAULT 0,
    vt_destructive_enabled         BOOLEAN             NOT NULL DEFAULT FALSE,
    vt_destructive_blacklist       BIGINT[]            NOT NULL DEFAULT ARRAY [] :: BIGINT[],
    vt_save_destructable           BOOLEAN             NOT NULL DEFAULT FALSE
);

CREATE TABLE vt_channel_messages
(
    message_id    BIGINT      NOT NULL PRIMARY KEY,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ,
    content       BYTEA       NOT NULL,
    guild_id      BIGINT      NOT NULL,
    channel_name  TEXT        NOT NULL,
    category_id   BIGINT,
    category_name TEXT,
    user_id       BIGINT      NOT NULL,
    user_name     TEXT        NOT NULL
);

# --- !Downs

DROP TABLE guild_settings;
DROP TABLE vt_channel_messages;

DROP TYPE VT_PERMISSION_VALUE;