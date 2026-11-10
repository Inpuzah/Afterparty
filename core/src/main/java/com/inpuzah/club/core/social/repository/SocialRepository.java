package com.inpuzah.club.core.social.repository;

import com.inpuzah.club.core.social.model.FriendRequest;
import com.inpuzah.club.core.social.model.FriendSummary;
import com.inpuzah.club.core.social.model.Relationship;
import com.inpuzah.club.core.social.model.SocialAction;
import com.inpuzah.club.core.social.model.SocialPreferences;
import com.inpuzah.club.core.social.model.SocialResult;

import java.util.List;
import java.util.UUID;

public interface SocialRepository {
    Relationship relationship(UUID player, UUID other);
    SocialResult mutate(SocialAction action, UUID actor, UUID other);
    List<FriendSummary> listFriends(UUID player, int offset, int limit);
    List<FriendRequest> listIncomingRequests(UUID player, int offset, int limit);
    List<FriendSummary> listBlocked(UUID player, int offset, int limit);
    SocialPreferences preferences(UUID player);
    SocialPreferences setPreferences(UUID player, boolean requestsEnabled);
    SocialPreferences setPartyPreferences(UUID player, boolean invitesEnabled);
}
