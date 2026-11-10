package com.inpuzah.club.core.party.repository;

import com.inpuzah.club.core.party.model.Party;
import com.inpuzah.club.core.party.PartyDeparture;
import com.inpuzah.club.core.party.model.PartyInvite;
import com.inpuzah.club.core.party.model.PartyOutcome;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Callers must establish ready online sessions for invitation creation/acceptance. */
public interface PartyRepository {
    PartyOutcome create(UUID actor);
    PartyOutcome invite(UUID actor, UUID target);
    PartyOutcome accept(UUID actor, UUID inviteId);
    PartyOutcome deny(UUID actor, UUID inviteId);
    PartyOutcome leave(UUID actor, Set<UUID> onlineMembers);
    PartyOutcome leaveIfCurrent(UUID actor, Set<UUID> onlineMembers, UUID partyId, long revision);
    PartyOutcome leaveDisconnected(UUID actor, Set<UUID> onlineMembers, PartyDeparture departure);
    PartyOutcome kick(UUID actor, UUID target);
    PartyOutcome promote(UUID actor, UUID target);
    PartyOutcome disband(UUID actor);
    PartyOutcome disbandIfCurrent(UUID actor, UUID partyId, long revision);
    Optional<Party> getParty(UUID player);
    /** Retains the transaction's epoch/profile locks until delivery returns; absence throws NOT_IN_PARTY. */
    void withParty(UUID player, java.util.function.Consumer<Party> delivery);
    List<PartyInvite> getInvites(UUID player, int offset, int limit);
}
