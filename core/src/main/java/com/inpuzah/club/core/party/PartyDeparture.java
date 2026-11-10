package com.inpuzah.club.core.party;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Reconnect cancellation competes with the repository's claim under its transaction locks. */
public final class PartyDeparture {
    private enum State { WAITING, CLAIMED, CANCELLED }
    private final AtomicReference<State> state = new AtomicReference<>(State.WAITING);
    private final CompletableFuture<Void> finished = new CompletableFuture<>();

    public boolean claim() {
        return state.compareAndSet(State.WAITING, State.CLAIMED);
    }

    public void cancel() {
        if (state.compareAndSet(State.WAITING, State.CANCELLED)) {
            finished.complete(null);
        }
    }

    public CompletableFuture<Void> finished() {
        return finished.copy();
    }

    public void complete() {
        finished.complete(null);
    }
}
