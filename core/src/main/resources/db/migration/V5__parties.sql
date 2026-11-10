CREATE TABLE party_instances (
    instance_id UUID PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at TIMESTAMPTZ,
    UNIQUE (owner_id, instance_id),
    CHECK (length(owner_id) BETWEEN 1 AND 64),
    CHECK (ended_at IS NULL OR ended_at >= started_at)
);

CREATE TABLE party_owners (
    owner_id VARCHAR(64) PRIMARY KEY,
    instance_id UUID NOT NULL UNIQUE,
    FOREIGN KEY (owner_id, instance_id) REFERENCES party_instances(owner_id, instance_id),
    CHECK (length(owner_id) BETWEEN 1 AND 64)
);

CREATE TABLE parties (
    id UUID PRIMARY KEY,
    leader_uuid UUID NOT NULL REFERENCES player_profiles(uuid),
    instance_id UUID NOT NULL REFERENCES party_instances(instance_id),
    max_members INTEGER NOT NULL DEFAULT 8 CHECK (max_members BETWEEN 2 AND 32),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    closed_at TIMESTAMPTZ,
    revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
    CHECK (updated_at >= created_at),
    CHECK (closed_at IS NULL OR (closed_at >= created_at AND closed_at <= updated_at))
);
CREATE INDEX idx_parties_instance_active ON parties(instance_id) WHERE closed_at IS NULL;

CREATE TABLE party_members (
    membership_id UUID PRIMARY KEY,
    party_id UUID NOT NULL REFERENCES parties(id),
    player_uuid UUID NOT NULL REFERENCES player_profiles(uuid),
    joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    left_at TIMESTAMPTZ,
    CHECK (left_at IS NULL OR left_at >= joined_at)
);
CREATE UNIQUE INDEX ux_active_party_membership ON party_members(player_uuid) WHERE left_at IS NULL;
CREATE INDEX idx_active_party_members ON party_members(party_id, joined_at, player_uuid) WHERE left_at IS NULL;

CREATE TABLE party_invites (
    id UUID PRIMARY KEY,
    party_id UUID NOT NULL REFERENCES parties(id),
    inviter_uuid UUID NOT NULL REFERENCES player_profiles(uuid),
    invitee_uuid UUID NOT NULL REFERENCES player_profiles(uuid),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at TIMESTAMPTZ NOT NULL,
    resolved_at TIMESTAMPTZ,
    resolution VARCHAR(20),
    CHECK (inviter_uuid <> invitee_uuid),
    CHECK (expires_at > created_at),
    CHECK (resolved_at IS NULL OR resolved_at >= created_at),
    CHECK ((resolved_at IS NULL AND resolution IS NULL)
        OR (resolved_at IS NOT NULL AND resolution IS NOT NULL
            AND resolution IN ('ACCEPTED', 'DENIED', 'CANCELLED', 'EXPIRED', 'PARTY_CLOSED')))
);
CREATE UNIQUE INDEX ux_unresolved_party_invite ON party_invites(party_id, invitee_uuid) WHERE resolved_at IS NULL;
CREATE INDEX idx_party_invites_recipient ON party_invites(invitee_uuid, expires_at) WHERE resolved_at IS NULL;
CREATE INDEX idx_party_invites_sender_time ON party_invites(inviter_uuid, created_at);

CREATE TABLE party_preferences (
    player_uuid UUID PRIMARY KEY REFERENCES player_profiles(uuid) ON DELETE CASCADE,
    invites_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Deferred validation permits atomic create, leadership transfer and close.
CREATE FUNCTION validate_party_membership() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    party_uuid UUID;
    party_row parties%ROWTYPE;
    member_count BIGINT;
BEGIN
    IF TG_TABLE_NAME = 'parties' THEN
        party_uuid := NEW.id;
    ELSIF TG_OP = 'DELETE' THEN
        party_uuid := OLD.party_id;
    ELSE
        party_uuid := NEW.party_id;
        IF TG_OP = 'UPDATE' AND NEW.party_id <> OLD.party_id THEN
            RAISE EXCEPTION 'Membership cannot move between parties' USING ERRCODE = '23514';
        END IF;
    END IF;
    SELECT * INTO party_row FROM parties WHERE id = party_uuid FOR NO KEY UPDATE;
    IF NOT FOUND THEN
        RETURN NULL;
    END IF;
    SELECT count(*) INTO member_count FROM party_members WHERE party_id = party_uuid AND left_at IS NULL;
    IF party_row.closed_at IS NULL THEN
        IF member_count < 1 OR member_count > party_row.max_members OR NOT EXISTS (
            SELECT 1 FROM party_members
            WHERE party_id = party_uuid AND player_uuid = party_row.leader_uuid AND left_at IS NULL
        ) THEN
            RAISE EXCEPTION 'Active party requires capacity and an active leader' USING ERRCODE = '23514';
        END IF;
    ELSIF member_count <> 0 THEN
        RAISE EXCEPTION 'Closed parties cannot have active members' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER validate_party_row
    AFTER INSERT OR UPDATE ON parties DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_party_membership();
CREATE CONSTRAINT TRIGGER validate_party_member_row
    AFTER INSERT OR UPDATE OR DELETE ON party_members DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_party_membership();
