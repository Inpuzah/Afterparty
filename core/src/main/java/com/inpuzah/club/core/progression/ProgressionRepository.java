package com.inpuzah.club.core.progression;

import java.util.UUID;

/** Blocking JDBC boundary. Call only from a worker thread. */
public interface ProgressionRepository {

    ProgressionSnapshot getProgress(UUID playerId);

    XpAwardResult awardXp(XpAwardRequest request);
}
