package com.inpuzah.club.core.economy;

import java.util.Objects;

public record CurrencyOperationResult(
        CurrencyOperation operation, Status status, long previousBalance, long balance, boolean duplicateReplay
) {

    public enum Status {
        SUCCESS,
        INSUFFICIENT_FUNDS
    }

    public CurrencyOperationResult {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(status, "status");
        if (previousBalance < 0 || balance < 0) {
            throw new IllegalArgumentException("balances must be nonnegative");
        }
        if (status == Status.INSUFFICIENT_FUNDS) {
            if (operation.type() != CurrencyOperationType.DEBIT || previousBalance >= operation.amount()
                    || balance != previousBalance || duplicateReplay) {
                throw new IllegalArgumentException("Insufficient funds must be an uncommitted debit without a balance change");
            }
        } else if (balance - previousBalance != operation.delta()) {
            throw new IllegalArgumentException("Balance change does not match the operation");
        }
    }

    public BalanceSnapshot snapshot() {
        return new BalanceSnapshot(operation.playerId(), balance);
    }
}
