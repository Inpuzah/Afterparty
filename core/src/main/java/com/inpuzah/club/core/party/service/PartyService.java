package com.inpuzah.club.core.party.service;

import com.inpuzah.club.core.party.model.Party;
import com.inpuzah.club.core.party.PartyDeparture;
import com.inpuzah.club.core.party.model.PartyInvite;
import com.inpuzah.club.core.party.model.PartyOutcome;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Online readiness is enforced by server-thread callers, not blocking storage workers. */
public interface PartyService {
    CompletableFuture<PartyOutcome> create(UUID actor);
    CompletableFuture<PartyOutcome> invite(UUID actor, UUID target);
    CompletableFuture<PartyOutcome> accept(UUID actor, UUID inviteId);
    CompletableFuture<PartyOutcome> deny(UUID actor, UUID inviteId);
    CompletableFuture<PartyOutcome> leave(UUID actor, Set<UUID> onlineMembers);
    CompletableFuture<PartyOutcome> leaveIfCurrent(UUID actor, Set<UUID> onlineMembers, UUID partyId, long revision);
    CompletableFuture<PartyOutcome> leaveDisconnected(UUID actor, Set<UUID> onlineMembers, PartyDeparture departure);
    CompletableFuture<PartyOutcome> kick(UUID actor, UUID target);
    CompletableFuture<PartyOutcome> promote(UUID actor, UUID target);
    CompletableFuture<PartyOutcome> disband(UUID actor);
    CompletableFuture<PartyOutcome> disbandIfCurrent(UUID actor, UUID partyId, long revision);
    CompletableFuture<Optional<Party>> getParty(UUID player);
    /** Runs delivery on a worker under membership locks; never wait on other party work from this callback. */
    CompletableFuture<Void> withParty(UUID player, java.util.function.Consumer<Party> delivery);
    CompletableFuture<List<PartyInvite>> getInvites(UUID player, int offset, int limit);
    int shutdown(Duration timeout) throws InterruptedException;
}
