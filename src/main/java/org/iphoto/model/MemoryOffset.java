package org.iphoto.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Parses one entry of IPhotoProperties.Memories.offsets - a plain integer ("6" - exact day, N
// months ago, no tolerance) or that same integer followed by one or both signed day-tolerance
// tokens, in either order ("6-2d+3d" or "6+3d-2d" are equivalent): "-Nd" widens the search window
// N days EARLIER than the anchor day, "+Nd" widens it N days LATER (closer to today). See
// PhotoService.getMemories, which searches every day in [anchor - beforeDays, anchor + afterDays]
// instead of only the exact anchor day.
public record MemoryOffset(int monthsAgo, int beforeDays, int afterDays) {

    private static final Pattern OFFSET_PATTERN = Pattern.compile("^(\\d+)((?:[+-]\\d+d)*)$");
    private static final Pattern TOLERANCE_TOKEN = Pattern.compile("([+-])(\\d+)d");

    public static MemoryOffset parse(String raw) {
        Matcher offset = OFFSET_PATTERN.matcher(raw.trim());
        if (!offset.matches()) {
            throw new IllegalArgumentException("Invalid memory offset '" + raw + "' - expected e.g. '6' or '6-2d+3d'");
        }
        int monthsAgo = Integer.parseInt(offset.group(1));
        int beforeDays = 0;
        int afterDays = 0;
        Matcher tokens = TOLERANCE_TOKEN.matcher(offset.group(2));
        while (tokens.find()) {
            int days = Integer.parseInt(tokens.group(2));
            if ("-".equals(tokens.group(1))) {
                beforeDays = days;
            } else {
                afterDays = days;
            }
        }
        return new MemoryOffset(monthsAgo, beforeDays, afterDays);
    }
}
