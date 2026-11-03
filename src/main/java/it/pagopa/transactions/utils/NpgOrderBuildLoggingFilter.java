package it.pagopa.transactions.utils;

import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.reactive.ClientHttpRequestDecorator;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.http.client.HttpClientRequest;
import reactor.netty.http.client.HttpClientResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Logs the HTTP request and response (method, url, headers and JSON bodies)
 * exchanged with NPG for the order/build API, masking session tokens, security
 * token and contract id. Secret headers are replaced by a fingerprint that
 * allows comparing them with a known value without logging them in clear.
 */
@Slf4j
public class NpgOrderBuildLoggingFilter implements ExchangeFilterFunction {

    private static final String ORDER_BUILD_PATH_SUFFIX = "/orders/build";
    private static final String CORRELATION_ID_HEADER = "Correlation-Id";
    private static final String MASK = "***";
    private static final Set<String> SECRET_HEADERS = Set.of("x-api-key", "authorization");

    private static final Pattern SESSION_TOKEN_PATTERN = Pattern.compile("(sessionToken=)[^&#\"\\\\]+");
    private static final Pattern SENSITIVE_JSON_FIELDS_PATTERN = Pattern
            .compile("(\"(?:securityToken|contractId)\"\\s*:\\s*\")[^\"]*(\")");

    @Override
    public Mono<ClientResponse> filter(
                                       ClientRequest request,
                                       ExchangeFunction next
    ) {
        if (!HttpMethod.POST.equals(request.method())
                || !isOrderBuildPath(request.url().getPath())) {
            return next.exchange(request);
        }
        String correlationId = request.headers().getFirst(CORRELATION_ID_HEADER);
        log.info(
                "NPG order/build request - correlationId: [{}], method: [{}], url: [{}], headers: [{}]",
                correlationId,
                request.method(),
                mask(request.url().toString()),
                formatHeaders(request.headers().entrySet())
        );
        ClientRequest loggingRequest = ClientRequest.from(request)
                .body(
                        (
                         outputMessage,
                         context
                        ) -> request.body().insert(
                                new ClientHttpRequestDecorator(outputMessage) {
                                    @Override
                                    public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                                        return super.writeWith(
                                                DataBufferUtils.join(body).doOnNext(
                                                        buffer -> log.info(
                                                                "NPG order/build request - correlationId: [{}], body: [{}]",
                                                                correlationId,
                                                                mask(buffer.toString(StandardCharsets.UTF_8))
                                                        )
                                                )
                                        );
                                    }
                                },
                                context
                        )
                )
                .build();
        return next.exchange(loggingRequest)
                .doOnNext(
                        response -> log.info(
                                "NPG order/build response - correlationId: [{}], status: [{}], headers: [{}]",
                                correlationId,
                                response.statusCode().value(),
                                formatHeaders(response.headers().asHttpHeaders().entrySet())
                        )
                )
                .map(
                        response -> response.mutate()
                                .body(
                                        body -> DataBufferUtils.join(body).doOnNext(
                                                buffer -> log.info(
                                                        "NPG order/build response - correlationId: [{}], status: [{}], body: [{}]",
                                                        correlationId,
                                                        response.statusCode().value(),
                                                        mask(buffer.toString(StandardCharsets.UTF_8))
                                                )
                                        ).flux()
                                )
                                .build()
                );
    }

    /**
     * Reactor Netty hook invoked after the request has been written on the wire:
     * logs the final headers really sent, including the ones added by Netty (e.g.
     * Host, User-Agent, Content-Length, Accept-Encoding)
     */
    public static void logWireRequest(
                                      HttpClientRequest request,
                                      Connection connection
    ) {
        if (!isOrderBuildPath(request.fullPath())) {
            return;
        }
        log.info(
                "NPG order/build wire request - correlationId: [{}], method: [{}], url: [{}], remoteAddress: [{}], headers: [{}]",
                request.requestHeaders().get(CORRELATION_ID_HEADER),
                request.method(),
                mask(request.resourceUrl()),
                connection.channel().remoteAddress(),
                formatHeaders(request.requestHeaders())
        );
    }

    /**
     * Reactor Netty hook invoked when the response headers have been received
     */
    public static void logWireResponse(
                                       HttpClientResponse response,
                                       Connection connection
    ) {
        if (!isOrderBuildPath(response.fullPath())) {
            return;
        }
        log.info(
                "NPG order/build wire response - correlationId: [{}], status: [{}], headers: [{}]",
                response.requestHeaders().get(CORRELATION_ID_HEADER),
                response.status(),
                formatHeaders(response.responseHeaders())
        );
    }

    private static boolean isOrderBuildPath(String path) {
        return path != null && path.endsWith(ORDER_BUILD_PATH_SUFFIX);
    }

    static <V> String formatHeaders(Iterable<? extends Map.Entry<String, V>> headers) {
        return StreamSupport.stream(headers.spliterator(), false)
                .map(header -> header.getKey() + ": " + formatHeaderValue(header.getKey(), header.getValue()))
                .collect(Collectors.joining(", "));
    }

    private static String formatHeaderValue(
                                            String name,
                                            Object value
    ) {
        if (value instanceof Iterable<?> values) {
            return StreamSupport.stream(values.spliterator(), false)
                    .map(v -> formatHeaderValue(name, v))
                    .collect(Collectors.joining(" | ", "[", "]"));
        }
        String stringValue = String.valueOf(value);
        return SECRET_HEADERS.contains(name.toLowerCase()) ? fingerprint(stringValue) : mask(stringValue);
    }

    /**
     * Describes a secret without disclosing it: compare the sha256 with the one of
     * the expected value (e.g. {@code printf '%s' "$KEY" | shasum -a 256})
     */
    static String fingerprint(String secret) {
        boolean showEdges = secret.length() >= 16;
        return "{length=%d, first4=%s, last4=%s, sha256=%s, leadingOrTrailingWhitespace=%b, nonPrintableChars=%b}"
                .formatted(
                        secret.length(),
                        showEdges ? secret.substring(0, 4) : MASK,
                        showEdges ? secret.substring(secret.length() - 4) : MASK,
                        sha256(secret),
                        !secret.equals(secret.strip()),
                        secret.chars().anyMatch(c -> c < 0x20 || c == 0x7f || c > 0x7e)
                );
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException e) {
            return "n/a";
        }
    }

    static String mask(String body) {
        String maskedSessionTokens = SESSION_TOKEN_PATTERN.matcher(body).replaceAll("$1" + MASK);
        return SENSITIVE_JSON_FIELDS_PATTERN.matcher(maskedSessionTokens).replaceAll("$1" + MASK + "$2");
    }
}
