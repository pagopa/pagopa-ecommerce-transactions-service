package it.pagopa.transactions.utils;

import ch.qos.logback.classic.spi.ILoggingEvent;
import co.elastic.logging.logback.EcsEncoder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
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

    private ObjectMapper objectMapper;

    public EcsEncoderLogMasker() {
        this.objectMapper = new ObjectMapper();
    }

    public void addMaskPattern(String maskPattern) {
        maskPatterns.add(maskPattern);
        patternString = Pattern.compile(String.join("|", maskPatterns), Pattern.MULTILINE);
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        byte[] encodedLog = super.encode(event);
        String clearLog = new String(encodedLog, StandardCharsets.UTF_8);
        try {
            JsonNode node = objectMapper.readTree(clearLog);
            String maskedMessage = maskMessage(node.get("message").asText());
            ((ObjectNode) node).put("message", maskedMessage);
            return objectMapper.writeValueAsString(node).getBytes(StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return maskMessage(clearLog).getBytes(StandardCharsets.UTF_8);
        }
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
