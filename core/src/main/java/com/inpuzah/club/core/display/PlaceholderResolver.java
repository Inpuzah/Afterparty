package com.inpuzah.club.core.display;

import com.inpuzah.club.core.session.ProfileSession;
import com.inpuzah.club.core.session.SessionState;

import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;

public final class PlaceholderResolver {
    private PlaceholderResolver() {}

    public static Map<String, String> values(ProfileSession session, String username, int online, String server) {
        String fallback = session == null || session.state() == SessionState.LOADING ? "Loading..." : "Unavailable";
        var profile = session != null && session.state() == SessionState.READY ? session.profile() : null;
        NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.ROOT);
        return Map.of("level", profile == null ? fallback : numbers.format(profile.level()),
                "coins", profile == null ? fallback : numbers.format(profile.coins()),
                "xp", profile == null ? fallback : numbers.format(profile.experience()),
                "username", username, "online", numbers.format(online), "server", server);
    }
}
