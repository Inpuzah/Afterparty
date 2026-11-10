package com.inpuzah.club.core.economy;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CurrencyOperationTest {

    private static final UUID ID = UUID.randomUUID();

    @Test
    void storesWholeCoinsAndSignedDeltasWithoutOverflow() {
        assertEquals(1_000_000_000L, BalanceSnapshot.MAX_BALANCE);
        assertEquals(Long.MAX_VALUE, operation(Long.MAX_VALUE, CurrencyOperationType.CREDIT).delta());
        assertEquals(-Long.MAX_VALUE, operation(Long.MAX_VALUE, CurrencyOperationType.DEBIT).delta());
        assertEquals(new BalanceSnapshot(ID, 0), new BalanceSnapshot(ID, 0));
        assertThrows(IllegalArgumentException.class, () -> new BalanceSnapshot(ID, -1));
        assertThrows(NullPointerException.class, () -> new BalanceSnapshot(null, 0));
    }

    @Test
    void validatesRequestAmountsAndRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> operation(0, CurrencyOperationType.CREDIT));
        assertThrows(IllegalArgumentException.class, () -> operation(-1, CurrencyOperationType.DEBIT));
        assertThrows(IllegalArgumentException.class, () -> operation(Long.MIN_VALUE, CurrencyOperationType.DEBIT));
        assertThrows(NullPointerException.class, () -> operation(1, null));
        assertThrows(NullPointerException.class,
                () -> new CurrencyOperation(null, ID, 1, CurrencyOperationType.CREDIT, "test", "reason", null));
        assertThrows(NullPointerException.class,
                () -> new CurrencyOperation(ID, null, 1, CurrencyOperationType.CREDIT, "test", "reason", null));
    }

    @Test
    void metadataIsBoundedAndNotSilentlyTrimmed() {
        for (String invalid : new String[]{"", " ", "bad\nmetadata", "x".repeat(81)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new CurrencyOperation(ID, ID, 1, CurrencyOperationType.CREDIT, invalid, "reason", null));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new CurrencyOperation(ID, ID, 1, CurrencyOperationType.CREDIT, "test", "x".repeat(256), null));
        CurrencyOperation request = new CurrencyOperation(ID, ID, 1, CurrencyOperationType.CREDIT, " test ", " reason ", null);
        assertEquals(" test ", request.source());
        assertEquals(" reason ", request.reason());
    }

    @Test
    void successMustMatchBalanceChange() {
        CurrencyOperation credit = operation(100, CurrencyOperationType.CREDIT);
        var result = new CurrencyOperationResult(credit, CurrencyOperationResult.Status.SUCCESS, 25, 125, false);
        assertEquals(new BalanceSnapshot(ID, 125), result.snapshot());
        assertThrows(IllegalArgumentException.class,
                () -> new CurrencyOperationResult(credit, CurrencyOperationResult.Status.SUCCESS, 25, 124, false));
        assertThrows(IllegalArgumentException.class,
                () -> new CurrencyOperationResult(credit, CurrencyOperationResult.Status.SUCCESS, -1, 99, false));
        CurrencyOperation debit = operation(100, CurrencyOperationType.DEBIT);
        assertEquals(0, new CurrencyOperationResult(debit, CurrencyOperationResult.Status.SUCCESS, 100, 0, true).balance());
    }

    @Test
    void insufficientFundsMustNotChangeBalanceOrClaimReplay() {
        CurrencyOperation debit = operation(100, CurrencyOperationType.DEBIT);
        var result = new CurrencyOperationResult(debit, CurrencyOperationResult.Status.INSUFFICIENT_FUNDS, 25, 25, false);
        assertEquals(25, result.balance());
        assertThrows(IllegalArgumentException.class,
                () -> new CurrencyOperationResult(debit, CurrencyOperationResult.Status.INSUFFICIENT_FUNDS, 25, 0, false));
        assertThrows(IllegalArgumentException.class,
                () -> new CurrencyOperationResult(debit, CurrencyOperationResult.Status.INSUFFICIENT_FUNDS, 100, 100, false));
        assertThrows(IllegalArgumentException.class,
                () -> new CurrencyOperationResult(debit, CurrencyOperationResult.Status.INSUFFICIENT_FUNDS, 25, 25, true));
        assertThrows(IllegalArgumentException.class, () -> new CurrencyOperationResult(
                operation(100, CurrencyOperationType.CREDIT), CurrencyOperationResult.Status.INSUFFICIENT_FUNDS, 25, 25, false));
    }

    private static CurrencyOperation operation(long amount, CurrencyOperationType type) {
        return new CurrencyOperation(ID, ID, amount, type, "test", "reason", null);
    }
}
