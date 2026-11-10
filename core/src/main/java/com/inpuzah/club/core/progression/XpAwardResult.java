package com.inpuzah.club.core.progression;

public record XpAwardResult(
        XpAwardRequest request, long previousExperience, long experience, boolean duplicateReplay
) {

    public XpAwardResult {
        if (request == null || previousExperience < 0
                || experience < request.amount() || experience - request.amount() != previousExperience) {
            throw new IllegalArgumentException("XP award result must match the request and previous total");
        }
    }

    public int previousLevel() {
        return LevelCalculator.levelFor(previousExperience);
    }

    public int level() {
        return LevelCalculator.levelFor(experience);
    }

    public ProgressionSnapshot snapshot() {
        return new ProgressionSnapshot(request.playerId(), experience);
    }
}
