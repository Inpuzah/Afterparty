package com.inpuzah.club.core.social.service;

import com.inpuzah.club.core.repository.ProfileStorageException;
import com.inpuzah.club.core.service.BoundedServiceExecutor;
import com.inpuzah.club.core.social.model.FriendRequest;
import com.inpuzah.club.core.social.model.FriendSummary;
import com.inpuzah.club.core.social.model.Relationship;
import com.inpuzah.club.core.social.model.SocialAction;
import com.inpuzah.club.core.social.model.SocialPreferences;
import com.inpuzah.club.core.social.model.SocialResult;
import com.inpuzah.club.core.social.repository.SocialException;
import com.inpuzah.club.core.social.repository.SocialRepository;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

public final class DefaultSocialService implements SocialService {
    private final SocialRepository repository;
    private final BoundedServiceExecutor workers;
    private final Logger logger;

    public DefaultSocialService(SocialRepository repository, ExecutorService executor, Logger logger) {
        this.repository = Objects.requireNonNull(repository);
        this.logger = Objects.requireNonNull(logger);
        workers = new BoundedServiceExecutor(executor,
                message -> new SocialException(SocialException.Code.SERVICE_UNAVAILABLE, message));
    }

    public static DefaultSocialService create(SocialRepository repository, int threads, int capacity, Logger logger) {
        return new DefaultSocialService(repository,
                BoundedServiceExecutor.workers("ClubCore-Social", threads, capacity), logger);
    }

    @Override
    public CompletableFuture<Relationship> relationship(UUID player, UUID other) {
        return submit(() -> {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(other, "other");
            if (player.equals(other)) {
                throw new IllegalArgumentException("Relationship requires two distinct profiles");
            }
            return repository.relationship(player, other);
        });
    }

    @Override
    public CompletableFuture<SocialResult> mutate(SocialAction action, UUID actor, UUID other) {
        return submit(() -> repository.mutate(Objects.requireNonNull(action, "action"),
                Objects.requireNonNull(actor, "actor"), Objects.requireNonNull(other, "other")));
    }

    @Override
    public CompletableFuture<List<FriendSummary>> listFriends(UUID player, int offset, int limit) {
        return submit(() -> {
            validatePage(player, offset, limit);
            return repository.listFriends(player, offset, limit);
        });
    }

    @Override
    public CompletableFuture<List<FriendRequest>> listIncomingRequests(UUID player, int offset, int limit) {
        return submit(() -> {
            validatePage(player, offset, limit);
            return repository.listIncomingRequests(player, offset, limit);
        });
    }

    @Override
    public CompletableFuture<List<FriendSummary>> listBlocked(UUID player, int offset, int limit) {
        return submit(() -> {
            validatePage(player, offset, limit);
            return repository.listBlocked(player, offset, limit);
        });
    }

    @Override
    public CompletableFuture<SocialPreferences> preferences(UUID player) {
        return submit(() -> repository.preferences(Objects.requireNonNull(player, "player")));
    }

    @Override
    public CompletableFuture<SocialPreferences> setPreferences(UUID player, boolean requestsEnabled) {
        return submit(() -> repository.setPreferences(Objects.requireNonNull(player, "player"), requestsEnabled));
    }

    private <T> CompletableFuture<T> submit(Supplier<T> operation) {
        return workers.submit(() -> {
            try {
                return operation.get();
            } catch (ProfileStorageException e) {
                logger.warn("Social storage operation failed; a mutation may have committed. Check state before retrying.", e);
                throw e;
            }

        });
    }

    private static void validatePage(UUID player, int offset, int limit) {
        Objects.requireNonNull(player, "player");
        if (offset < 0 || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("List offset must be nonnegative and limit must be 1-100");
        }
    }

    @Override
    public int shutdown(Duration timeout) throws InterruptedException {
        return workers.shutdown(timeout);
    }
}
