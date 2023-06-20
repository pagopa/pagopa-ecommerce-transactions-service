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
    private final List<String> maskPatterns = new ArrayList<>();

    public void setMaskPattern(String maskPatter) {
        this.maskPatterns.add(maskPatter);
    }

    public void addMaskPattern(String maskPattern) {
        maskPatterns.add(maskPattern);
        patternString = Pattern.compile(String.join("|", maskPatterns), Pattern.MULTILINE);
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        byte[] encodedLog = super.encode(event);
        String clearLog = new String(encodedLog, StandardCharsets.UTF_8);
        return maskMessage(clearLog).getBytes(StandardCharsets.UTF_8);
    }

    private String maskMessage(String message) {
        StringBuilder sb = new StringBuilder(message);
        Matcher matcher = patternString.matcher(sb);
        while (matcher.find()) {
            IntStream.rangeClosed(1, matcher.groupCount()).forEach(group -> {
                if (matcher.group(group) != null) {
                    IntStream.range(
                            matcher.start(group) + getOffset(matcher.group()),
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
