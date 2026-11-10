package com.inpuzah.club.core.social.service;

import com.inpuzah.club.core.social.model.FriendRequest;
import com.inpuzah.club.core.social.model.FriendSummary;
import com.inpuzah.club.core.social.model.Relationship;
import com.inpuzah.club.core.social.model.SocialAction;
import com.inpuzah.club.core.social.model.SocialPreferences;
import com.inpuzah.club.core.social.model.SocialResult;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface SocialService {
    CompletableFuture<Relationship> relationship(UUID player, UUID other);
    CompletableFuture<SocialResult> mutate(SocialAction action, UUID actor, UUID other);
    CompletableFuture<List<FriendSummary>> listFriends(UUID player, int offset, int limit);
    CompletableFuture<List<FriendRequest>> listIncomingRequests(UUID player, int offset, int limit);
    CompletableFuture<List<FriendSummary>> listBlocked(UUID player, int offset, int limit);
    CompletableFuture<SocialPreferences> preferences(UUID player);
    CompletableFuture<SocialPreferences> setPreferences(UUID player, boolean requestsEnabled);
    CompletableFuture<SocialPreferences> setPartyPreferences(UUID player, boolean invitesEnabled);
    int shutdown(Duration timeout) throws InterruptedException;
}
