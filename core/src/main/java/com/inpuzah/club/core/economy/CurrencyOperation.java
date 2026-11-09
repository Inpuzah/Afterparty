package com.inpuzah.club.core.economy;

import java.util.Objects;
import java.util.UUID;

public record CurrencyOperation(
        UUID operationId, UUID playerId, long amount, CurrencyOperationType type,
        String source, String reason, UUID actorId
) {

    public CurrencyOperation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(type, "type");
        if (amount <= 0) {
            throw new IllegalArgumentException("Coin amount must be positive");
        }
        validateText(source, "source", 80);
        validateText(reason, "reason", 255);
    }

    public long delta() {
        return type == CurrencyOperationType.CREDIT ? amount : -amount;
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
