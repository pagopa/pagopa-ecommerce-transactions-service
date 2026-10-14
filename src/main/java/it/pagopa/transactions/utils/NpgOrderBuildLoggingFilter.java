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

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Logs the JSON request and response bodies exchanged with NPG for the
 * order/build API, masking session tokens, security token and contract id
 */
@Slf4j
public class NpgOrderBuildLoggingFilter implements ExchangeFilterFunction {

    private static final String ORDER_BUILD_PATH_SUFFIX = "/orders/build";
    private static final String CORRELATION_ID_HEADER = "Correlation-Id";
    private static final String MASK = "***";

    private static final Pattern SESSION_TOKEN_PATTERN = Pattern.compile("(sessionToken=)[^&#\"\\\\]+");
    private static final Pattern SENSITIVE_JSON_FIELDS_PATTERN = Pattern
            .compile("(\"(?:securityToken|contractId)\"\\s*:\\s*\")[^\"]*(\")");

    @Override
    public Mono<ClientResponse> filter(
                                       ClientRequest request,
                                       ExchangeFunction next
    ) {
        if (!HttpMethod.POST.equals(request.method())
                || !request.url().getPath().endsWith(ORDER_BUILD_PATH_SUFFIX)) {
            return next.exchange(request);
        }
        String correlationId = request.headers().getFirst(CORRELATION_ID_HEADER);
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

    static String mask(String body) {
        String maskedSessionTokens = SESSION_TOKEN_PATTERN.matcher(body).replaceAll("$1" + MASK);
        return SENSITIVE_JSON_FIELDS_PATTERN.matcher(maskedSessionTokens).replaceAll("$1" + MASK + "$2");
    }
}
