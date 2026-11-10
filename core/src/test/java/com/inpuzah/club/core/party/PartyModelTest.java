package com.inpuzah.club.core.party;

import com.inpuzah.club.core.party.model.Party;
import com.inpuzah.club.core.party.model.PartyInvite;
import com.inpuzah.club.core.party.model.PartyMember;
import com.inpuzah.club.core.party.model.PartyPreferences;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PartyModelTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID INSTANCE = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID MEMBER = UUID.fromString("80000000-0000-0000-0000-000000000004");
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final PartyMember OWNER = new PartyMember(LEADER, "Leader", NOW);
    private static final PartyMember SECOND = new PartyMember(MEMBER, "Member", NOW.plusSeconds(1));

    @Test
    void soleMemberIsLeaderAndCapacityIsNotReservedByAnInvitation() {
        var party = party(8, List.of(OWNER));
        assertEquals(ID, party.id());
        assertEquals(INSTANCE, party.instanceId());
        assertEquals(0, party.revision());
        assertTrue(party.isLeader(LEADER));
        assertTrue(party.isMember(LEADER));
        assertFalse(party.isLeader(MEMBER));
        assertFalse(party.isMember(MEMBER));
        assertFalse(party.isFull());
        new PartyInvite(UUID.randomUUID(), ID, LEADER, MEMBER, NOW, NOW.plusSeconds(120));
        assertEquals(1, party.members().size());
        assertFalse(party.isMember(MEMBER));
    }

    @Test
    void membersAreDefensivelyCopiedAndCannotBeChangedByCallers() {
        var input = new ArrayList<>(List.of(OWNER, SECOND));
        var party = party(8, input);
        input.clear();
        assertEquals(List.of(OWNER, SECOND), party.members());
        assertThrows(UnsupportedOperationException.class, () -> party.members().clear());
    }

    @Test
    void membersAreOrderedByJoinTimeThenPostgresCompatibleUuidOrder() {
        var simultaneous = new PartyMember(MEMBER, "Member", NOW);
        assertTrue(MEMBER.compareTo(LEADER) < 0);
        assertEquals(List.of(OWNER, simultaneous), party(8, List.of(simultaneous, OWNER)).members());
        assertEquals(List.of(OWNER, SECOND), party(8, List.of(SECOND, OWNER)).members());
    }

    @Test
    void capacityAcceptsEndpointsAndRejectsOversizeSnapshots() {
        assertEquals(8, Party.DEFAULT_MAX_MEMBERS);
        assertTrue(party(2, List.of(OWNER, SECOND)).isFull());
        assertDoesNotThrow(() -> party(32, List.of(OWNER)));
        assertThrows(IllegalArgumentException.class, () -> party(1, List.of(OWNER)));
        assertThrows(IllegalArgumentException.class, () -> party(33, List.of(OWNER)));
        var third = new PartyMember(UUID.randomUUID(), "Third", NOW);
        assertThrows(IllegalArgumentException.class, () -> party(2, List.of(OWNER, SECOND, third)));
    }

    @Test
    void activePartyRequiresExactlyOneLeaderIdentityAmongDistinctMembers() {
        assertThrows(IllegalArgumentException.class, () -> party(8, List.of()));
        assertThrows(IllegalArgumentException.class, () -> party(8, List.of(SECOND)));
        assertThrows(IllegalArgumentException.class, () -> party(8, List.of(OWNER, OWNER)));
        assertThrows(IllegalArgumentException.class,
                () -> party(8, List.of(OWNER, new PartyMember(LEADER, "Renamed", NOW))));
    }

    @Test
    void duplicateNamesDoNotReplaceUuidIdentity() {
        var sameName = new PartyMember(MEMBER, OWNER.username(), NOW);
        var party = party(8, List.of(OWNER, sameName));
        assertEquals(2, party.members().size());
        assertTrue(party.isMember(MEMBER));
        assertFalse(party.isLeader(MEMBER));
    }

    @Test
    void snapshotTimesAndRevisionMustBeValid() {
        assertThrows(IllegalArgumentException.class,
                () -> new Party(ID, INSTANCE, LEADER, NOW, NOW.minusNanos(1), 0, 8, List.of(OWNER)));
        assertThrows(IllegalArgumentException.class,
                () -> new Party(ID, INSTANCE, LEADER, NOW, NOW, -1, 8, List.of(OWNER)));
        assertDoesNotThrow(() -> new Party(ID, INSTANCE, LEADER, NOW, NOW, Long.MAX_VALUE, 8, List.of(OWNER)));
        assertThrows(IllegalArgumentException.class,
                () -> party(8, List.of(new PartyMember(LEADER, "Leader", NOW.minusNanos(1)))));
        assertThrows(IllegalArgumentException.class,
                () -> party(8, List.of(OWNER, new PartyMember(MEMBER, "Member", NOW.plusSeconds(3)))));
    }

    @Test
    void partyRejectsNullIdentityTimesAndMembers() {
        assertThrows(NullPointerException.class, () -> new Party(null, INSTANCE, LEADER, NOW, NOW, 0, 8, List.of(OWNER)));
        assertThrows(NullPointerException.class, () -> new Party(ID, null, LEADER, NOW, NOW, 0, 8, List.of(OWNER)));
        assertThrows(NullPointerException.class, () -> new Party(ID, INSTANCE, null, NOW, NOW, 0, 8, List.of(OWNER)));
        assertThrows(NullPointerException.class, () -> new Party(ID, INSTANCE, LEADER, null, NOW, 0, 8, List.of(OWNER)));
        assertThrows(NullPointerException.class, () -> new Party(ID, INSTANCE, LEADER, NOW, null, 0, 8, List.of(OWNER)));
        assertThrows(NullPointerException.class, () -> party(8, null));
        var input = new ArrayList<PartyMember>();
        input.add(null);
        assertThrows(NullPointerException.class, () -> party(8, input));
        var party = party(8, List.of(OWNER));
        assertThrows(NullPointerException.class, () -> party.isLeader(null));
        assertThrows(NullPointerException.class, () -> party.isMember(null));
    }

    @Test
    void memberUsesExistingProfileNameRulesAndRequiresIdentityAndJoinTime() {
        assertThrows(NullPointerException.class, () -> new PartyMember(null, "Leader", NOW));
        assertThrows(NullPointerException.class, () -> new PartyMember(LEADER, null, NOW));
        assertThrows(NullPointerException.class, () -> new PartyMember(LEADER, "Leader", null));
        for (var name : List.of("", "has space", "tab\t", "Seventeen_Chars_1")) {
            assertThrows(IllegalArgumentException.class, () -> new PartyMember(LEADER, name, NOW));
        }
        assertDoesNotThrow(() -> new PartyMember(LEADER, "Sixteen_Chars_12", NOW));
    }

    @Test
    void inviteIdentityIsIndependentOfMutableNamesAndExpiresAtExactBoundary() {
        var invite = new PartyInvite(INSTANCE, ID, LEADER, MEMBER, NOW, NOW.plusSeconds(120));
        assertEquals(INSTANCE, invite.id());
        assertEquals(ID, invite.partyId());
        assertEquals(LEADER, invite.inviterId());
        assertEquals(MEMBER, invite.inviteeId());
        assertFalse(invite.isExpired(NOW.plusSeconds(120).minusNanos(1)));
        assertTrue(invite.isExpired(NOW.plusSeconds(120)));
        assertTrue(invite.isExpired(NOW.plusSeconds(121)));
        assertThrows(NullPointerException.class, () -> invite.isExpired(null));
    }

    @Test
    void selfInvitesAndNonpositiveTtlFailExplicitly() {
        assertThrows(IllegalArgumentException.class,
                () -> new PartyInvite(INSTANCE, ID, LEADER, LEADER, NOW, NOW.plusSeconds(120)));
        assertThrows(IllegalArgumentException.class,
                () -> new PartyInvite(INSTANCE, ID, LEADER, MEMBER, NOW, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new PartyInvite(INSTANCE, ID, LEADER, MEMBER, NOW, NOW.minusNanos(1)));
    }

    @Test
    void invitationRejectsAllMissingRequiredFields() {
        assertThrows(NullPointerException.class, () -> new PartyInvite(null, ID, LEADER, MEMBER, NOW, NOW.plusSeconds(120)));
        assertThrows(NullPointerException.class, () -> new PartyInvite(INSTANCE, null, LEADER, MEMBER, NOW, NOW.plusSeconds(120)));
        assertThrows(NullPointerException.class, () -> new PartyInvite(INSTANCE, ID, null, MEMBER, NOW, NOW.plusSeconds(120)));
        assertThrows(NullPointerException.class, () -> new PartyInvite(INSTANCE, ID, LEADER, null, NOW, NOW.plusSeconds(120)));
        assertThrows(NullPointerException.class, () -> new PartyInvite(INSTANCE, ID, LEADER, MEMBER, null, NOW.plusSeconds(120)));
        assertThrows(NullPointerException.class, () -> new PartyInvite(INSTANCE, ID, LEADER, MEMBER, NOW, null));
    }

    @Test
    void personalInvitePolicyIsAnIndependentImmutableChoice() {
        var everyone = new PartyPreferences(LEADER, true);
        var nobody = new PartyPreferences(LEADER, false);
        assertEquals(LEADER, everyone.playerId());
        assertTrue(everyone.invitesEnabled());
        assertFalse(nobody.invitesEnabled());
        assertNotEquals(everyone, nobody);
        assertThrows(NullPointerException.class, () -> new PartyPreferences(null, true));
    }

    private static Party party(int maxMembers, List<PartyMember> members) {
        return new Party(ID, INSTANCE, LEADER, NOW, NOW.plusSeconds(2), 0, maxMembers, members);
    }
}
