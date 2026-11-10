package com.inpuzah.club.core.display;

import com.inpuzah.club.core.config.ConfigException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class DisplayText {
    private static final Set<String> TOKENS = Set.of("level", "coins", "online", "server", "username", "xp");
    private static final Pattern TOKEN = Pattern.compile("\\{([^{}]*)}");
    private static final MiniMessage MARKUP = MiniMessage.builder().strict(true).tags(TagResolver.resolver(
            StandardTags.color(), StandardTags.decorations(), StandardTags.gradient(), StandardTags.rainbow())).build();
    private final String template;
    private final int limit;

    public DisplayText(String input, int limit) {
        if (input.length() > 1024 || input.codePoints().anyMatch(Character::isISOControl)) {
            throw new ConfigException("Display text must be at most 1024 characters without control characters");
        }
        var matcher = TOKEN.matcher(input);
        StringBuilder converted = new StringBuilder();
        while (matcher.find()) {
            if (!TOKENS.contains(matcher.group(1))) {
                throw new ConfigException("Unknown display placeholder: " + matcher.group());
            }
            matcher.appendReplacement(converted, "<club_" + matcher.group(1) + ">");
        }
        matcher.appendTail(converted);
        template = converted.toString();
        this.limit = limit;
        if (template.contains("{") || template.contains("}")) {
            throw new ConfigException("Malformed display placeholder");
        }
        try {
            render(Map.of("level", "2,147,483,647", "coins", "9,223,372,036,854,775,807", "xp", "9,223,372,036,854,775,807",
                    "online", "2,147,483,647", "server", "s".repeat(32), "username", "u".repeat(16)));
        } catch (IllegalArgumentException | ParsingException e) {
            throw new ConfigException("Invalid display text: " + e.getMessage());
        }
    }

    public Component render(Map<String, String> values) {
        TagResolver.Builder tags = TagResolver.builder();
        TOKENS.forEach(key -> tags.resolver(Placeholder.unparsed("club_" + key, values.getOrDefault(key, "--"))));
        Component result = MARKUP.deserialize(template, tags.build());
        String plain = PlainTextComponentSerializer.plainText().serialize(result);
        if (plain.codePointCount(0, plain.length()) > limit) {
            throw new IllegalArgumentException("Rendered display text exceeds " + limit + " characters");
        }
        // Unknown/unsupported tags are preserved literally by MiniMessage; reject rather than display them.
        if (plain.contains("<") || plain.contains(">")) {
            throw new IllegalArgumentException("Unknown or unsupported display formatting tag");
        }
        return result;
    }
}
