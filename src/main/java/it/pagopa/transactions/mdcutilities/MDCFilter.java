package it.pagopa.transactions.mdcutilities;

import io.micrometer.context.ContextRegistry;
import com.azure.cosmos.implementation.apachecommons.collections.CollectionUtils;
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

import java.util.*;

@Component
@Slf4j
public class MDCFilter implements WebFilter {

    public static final String HEADER_TRANSACTION_ID = "x-transaction-id";
    public static final String HEADER_RPT_ID = "x-rpt-ids";
    public static final String HEADER_NPG_CORRELATION_ID = "x-correlation-id";
    public static final String HEADER_USER_ID = "x-user-id";
    public static final String HEADER_CLIENT_ID = "x-client-id";

    @Override
    public Mono<Void> filter(
                             ServerWebExchange exchange,
                             WebFilterChain chain
    ) {
        final HttpHeaders headers = exchange.getRequest().getHeaders();
        final String transactionId = getHeaderValueWithDefault(
                headers,
                HEADER_TRANSACTION_ID,
                LogTracingUtils.AttributeKeys.CTX_TRANSACTION_ID
        );
        final String rptId = getHeaderValueWithDefault(
                headers,
                HEADER_RPT_ID,
                LogTracingUtils.AttributeKeys.CTX_RPT_IDS
        );
        final String correlationId = getHeaderValueWithDefault(
                headers,
                HEADER_NPG_CORRELATION_ID,
                LogTracingUtils.AttributeKeys.CORRELATION_ID
        );
        final String userId = getHeaderValueWithDefault(
                headers,
                HEADER_USER_ID,
                LogTracingUtils.AttributeKeys.CTX_USER_ID
        );
        final String clientId = getHeaderValueWithDefault(
                headers,
                HEADER_CLIENT_ID,
                LogTracingUtils.AttributeKeys.CTX_CLIENT_ID
        );

        return chain.filter(exchange)
                .contextWrite(
                        ctx -> LogTracingUtils.enrichContextForEvent(
                                Map.of(
                                        LogTracingUtils.AttributeKeys.CTX_TRANSACTION_ID,
                                        transactionId,
                                        LogTracingUtils.AttributeKeys.CTX_RPT_IDS,
                                        rptId,
                                        LogTracingUtils.AttributeKeys.CORRELATION_ID,
                                        correlationId,
                                        LogTracingUtils.AttributeKeys.CTX_USER_ID,
                                        userId,
                                        LogTracingUtils.AttributeKeys.CTX_CLIENT_ID,
                                        clientId,
                                        LogTracingUtils.AttributeKeys.EVENT_ACTION,
                                        "%s %s".formatted(
                                                exchange.getRequest().getMethod().name(),
                                                exchange.getRequest().getURI().getPath()
                                        )
                                ),
                                ctx
                        )
                );
    }

    private String getHeaderValueWithDefault(
                                             HttpHeaders headers,
                                             String header,
                                             LogTracingUtils.AttributeKeys attribute
    ) {
        return CollectionUtils.emptyIfNull(headers.get(header))
                .stream()
                .findFirst()
                .orElse(attribute.getDefaultValue());
    }

    /**
     * Initializes the Micrometer context propagation registry. This method runs
     * once at application startup. It filters the tracing keys to include only the
     * `contextBound` ones, instructing the Spring Boot 3 infrastructure on how to
     * read, write, and clear the MDC `ThreadLocal` values for these specific keys.
     */
    @PostConstruct
    public void initMdcMicrometerRegistry() {
        Hooks.enableAutomaticContextPropagation();
        Arrays.stream(LogTracingUtils.AttributeKeys.values())
                .forEach(
                        entry -> ContextRegistry.getInstance()
                                .registerThreadLocalAccessor(
                                        entry.getKey(),
                                        () -> MDC.get(entry.getKey()),
                                        value -> MDC.put(entry.getKey(), value),
                                        () -> MDC.remove(entry.getKey())
                                )
                );
    }
}
