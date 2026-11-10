package com.inpuzah.club.core.social.model;

import java.util.UUID;

/** Presence is added by the Paper command on the main thread, never by JDBC. */
public record FriendSummary(UUID playerId, String username) {}
