package com.inpuzah.club.core.progression;

import java.util.Objects;
import java.util.UUID;

public record XpAwardRequest(
        UUID operationId, UUID playerId, long amount, String source, String reason, UUID actorId
) {

    public XpAwardRequest {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        if (amount <= 0) {
            throw new IllegalArgumentException("XP award amount must be positive");
        }
        validateText(source, "source", 80);
        validateText(reason, "reason", 255);
    }

    private static void validateText(String text, String field, int maxLength) {
        Objects.requireNonNull(text, field);
        if (text.isBlank() || text.codePointCount(0, text.length()) > maxLength
                || text.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " must contain 1-" + maxLength
                    + " characters and no control characters");
        }
    }
}
