CREATE TABLE display_preferences (
    player_uuid UUID PRIMARY KEY REFERENCES player_profiles(uuid),
    show_bossbar BOOLEAN NOT NULL DEFAULT TRUE,
    show_scoreboard BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
