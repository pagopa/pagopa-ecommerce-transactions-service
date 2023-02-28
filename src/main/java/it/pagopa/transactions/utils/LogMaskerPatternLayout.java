package it.pagopa.transactions.utils;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class LogMaskerPatternLayout extends PatternLayout {
    private Pattern pattern;
    private List<String> maskPatterns = new ArrayList<>();

    public void addMaskPattern(String maskPattern) {
        maskPatterns.add(maskPattern);
        pattern = Pattern.compile(maskPatterns.stream().collect(Collectors.joining("|")), Pattern.MULTILINE);
    }

    @Override
    public String doLayout(ILoggingEvent event) {
        return maskMessage(super.doLayout(event));
    }

    private String maskMessage(String message) {
        StringBuilder sb = new StringBuilder(message);
        Matcher matcher = pattern.matcher(sb);
        while (matcher.find()) {
            IntStream.rangeClosed(1, matcher.groupCount()).forEach(group -> {
                if (matcher.group(group) != null) {
                    int offset = getOffset(matcher.group());
                    IntStream.range(
                            matcher.start(group) + offset,
                            matcher.end(group)
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
