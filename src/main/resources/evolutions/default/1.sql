# --- !Ups

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

DROP TABLE vt_channel_messages;
