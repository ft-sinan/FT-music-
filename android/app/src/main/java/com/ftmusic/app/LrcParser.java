package com.ftmusic.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses line-timed LRC without assuming that a provider always returns valid timestamps. */
final class LrcParser {
    static final class Line {
        final long atMillis;
        final String text;
        Line(long atMillis, String text) { this.atMillis = atMillis; this.text = text; }
    }
    private static final Pattern TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("(?im)^\\s*\\[offset:\\s*([+-]?\\d{1,8})\\s*\\]\\s*$");
    private static final int MAX_LINES = 10000;

    static List<Line> parse(String input) {
        List<Line> lines = new ArrayList<Line>();
        if (input == null || input.length() > 1024 * 1024) return lines;
        long offset = 0;
        Matcher offsetMatch = OFFSET.matcher(input);
        if (offsetMatch.find()) try { offset = Long.parseLong(offsetMatch.group(1)); } catch (NumberFormatException ignored) { }
        for (String raw : input.split("\\r?\\n")) {
            Matcher matcher = TIME.matcher(raw);
            List<Long> stamps = new ArrayList<Long>();
            int last = -1;
            while (matcher.find() && stamps.size() < 100) {
                try {
                    long minutes = Long.parseLong(matcher.group(1));
                    int seconds = Integer.parseInt(matcher.group(2));
                    if (seconds >= 60) continue;
                    String fraction = matcher.group(3);
                    int millis = fraction == null ? 0 : Integer.parseInt(fraction) * (fraction.length() == 1 ? 100 : fraction.length() == 2 ? 10 : 1);
                    stamps.add(Math.max(0, minutes * 60000 + seconds * 1000L + millis + offset));
                    last = matcher.end();
                } catch (NumberFormatException ignored) { }
            }
            if (stamps.isEmpty() || last < 0) continue;
            String text = raw.substring(last).trim();
            if (text.isEmpty()) continue;
            if (text.length() > 500) text = text.substring(0, 500);
            for (Long stamp : stamps) { lines.add(new Line(stamp, text)); if (lines.size() >= MAX_LINES) break; }
            if (lines.size() >= MAX_LINES) break;
        }
        Collections.sort(lines, new Comparator<Line>() { @Override public int compare(Line a, Line b) { return Long.compare(a.atMillis, b.atMillis); } });
        return lines;
    }

    static int currentLine(List<Line> lines, long positionMillis) {
        int lo = 0, hi = lines.size() - 1, best = -1;
        while (lo <= hi) {
            int middle = (lo + hi) >>> 1;
            if (lines.get(middle).atMillis <= positionMillis) { best = middle; lo = middle + 1; }
            else hi = middle - 1;
        }
        return best;
    }
    private LrcParser() { }
}
