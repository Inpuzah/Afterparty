CREATE TABLE player_profiles (
    uuid            UUID PRIMARY KEY,
    username        VARCHAR(16) NOT NULL,
    first_joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    level           INTEGER     NOT NULL DEFAULT 1 CHECK (level >= 1),
    experience      BIGINT      NOT NULL DEFAULT 0 CHECK (experience >= 0),
    coins           BIGINT      NOT NULL DEFAULT 0 CHECK (coins >= 0),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
