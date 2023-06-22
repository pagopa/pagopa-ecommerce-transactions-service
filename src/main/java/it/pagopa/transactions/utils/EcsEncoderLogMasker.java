package it.pagopa.transactions.utils;

import ch.qos.logback.classic.spi.ILoggingEvent;
import co.elastic.logging.logback.EcsEncoder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

public class EcsEncoderLogMasker extends EcsEncoder {
    private Pattern patternString;
    private Pattern safePatternString;
    private final List<String> maskPatterns = new ArrayList<>();
    private final List<String> safePatterns = new ArrayList<>();

    public void addMaskPattern(String maskPattern) {
        maskPatterns.add(maskPattern);
        patternString = Pattern.compile(String.join("|", maskPatterns), Pattern.MULTILINE);
    }

    public void addSafePattern(String safePattern) {
        safePatterns.add(safePattern);
        safePatternString = Pattern.compile(String.join("|", safePatterns), Pattern.MULTILINE);
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        byte[] encodedLog = super.encode(event);
        String clearLog = new String(encodedLog, StandardCharsets.UTF_8);
        return maskMessage(clearLog).getBytes(StandardCharsets.UTF_8);
    }

    private String maskMessage(String message) {
        StringBuilder sb = new StringBuilder(message);
        ArrayList<List<Integer>> safeIndexes = new ArrayList<>();
        Matcher matcher = patternString.matcher(sb);
        if (safePatternString != null) {
            Matcher safePatternMatcher = safePatternString.matcher(sb);
            while (safePatternMatcher.find()) {
                IntStream.rangeClosed(1, safePatternMatcher.groupCount()).forEach(group -> {
                    if (safePatternMatcher.group(group) != null) {
                        safeIndexes.add(
                                Arrays.asList(
                                        safePatternMatcher.start(group),
                                        safePatternMatcher.end(group)
                                )
                        );
                    }
                });
            }
        }
        while (matcher.find()) {
            IntStream.rangeClosed(1, matcher.groupCount()).forEach(group -> {
                if (matcher.group(group) != null) {
                    IntStream.range(
                            matcher.start(group) + getOffset(matcher.group()),
                            matcher.end(group)
                    ).filter(
                            i -> !safeIndexes.stream().anyMatch(
                                    safeIndexInterval -> safeIndexInterval.get(0) <= i
                                            && safeIndexInterval.get(1) > i
                            )
                    ).forEach(i -> sb.setCharAt(i, '*'));
                }
            });
        }
        return sb.toString();
    }

    private int getOffset(String group) {
        return Arrays.stream(group.split(" ")).filter(s -> s.length() < group.length()).findFirst().orElse("").length();
    }
}
