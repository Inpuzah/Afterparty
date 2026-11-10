package com.inpuzah.club.core.social.model;

import java.time.Instant;
import java.util.UUID;

public record FriendRequest(UUID senderId, String senderName, Instant expiresAt) {}
