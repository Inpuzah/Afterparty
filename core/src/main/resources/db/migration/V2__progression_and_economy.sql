CREATE TABLE currency_transactions (
    operation_id UUID PRIMARY KEY,
    player_uuid UUID NOT NULL REFERENCES player_profiles(uuid),
    amount_delta BIGINT NOT NULL CHECK (amount_delta <> 0),
    balance_after BIGINT NOT NULL CHECK (balance_after >= 0),
    operation_type VARCHAR(24) NOT NULL,
    source VARCHAR(80) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    actor_uuid UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_currency_transactions_player_time
    ON currency_transactions(player_uuid, created_at DESC);

CREATE TABLE experience_awards (
    operation_id UUID PRIMARY KEY,
    player_uuid UUID NOT NULL REFERENCES player_profiles(uuid),
    amount BIGINT NOT NULL CHECK (amount > 0),
    experience_after BIGINT NOT NULL CHECK (experience_after >= 0),
    level_after INTEGER NOT NULL CHECK (level_after >= 1),
    source VARCHAR(80) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    actor_uuid UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_experience_awards_player_time
    ON experience_awards(player_uuid, created_at DESC);
