CREATE TABLE friendships (
    player_low UUID NOT NULL REFERENCES player_profiles(uuid) ON DELETE CASCADE,
    player_high UUID NOT NULL REFERENCES player_profiles(uuid) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (player_low, player_high),
    CHECK (player_low < player_high)
);
CREATE INDEX idx_friendships_high ON friendships(player_high);

CREATE TABLE friend_requests (
    sender_uuid UUID NOT NULL REFERENCES player_profiles(uuid) ON DELETE CASCADE,
    recipient_uuid UUID NOT NULL REFERENCES player_profiles(uuid) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '7 days'),
    resolved_at TIMESTAMPTZ,
    PRIMARY KEY (sender_uuid, recipient_uuid),
    CHECK (sender_uuid <> recipient_uuid),
    CHECK (expires_at > created_at),
    CHECK (resolved_at IS NULL OR resolved_at >= created_at)
);
CREATE INDEX idx_friend_requests_recipient ON friend_requests(recipient_uuid, expires_at);
CREATE INDEX idx_friend_requests_sender_time ON friend_requests(sender_uuid, created_at);

CREATE TABLE player_blocks (
    blocker_uuid UUID NOT NULL REFERENCES player_profiles(uuid) ON DELETE CASCADE,
    blocked_uuid UUID NOT NULL REFERENCES player_profiles(uuid) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (blocker_uuid, blocked_uuid),
    CHECK (blocker_uuid <> blocked_uuid)
);
CREATE INDEX idx_player_blocks_target ON player_blocks(blocked_uuid);

CREATE TABLE social_preferences (
    player_uuid UUID PRIMARY KEY REFERENCES player_profiles(uuid) ON DELETE CASCADE,
    friend_requests_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
