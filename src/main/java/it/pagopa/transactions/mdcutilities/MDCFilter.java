package it.pagopa.transactions.mdcutilities;

import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

@Component
@Slf4j
public class MDCFilter implements WebFilter {

    public static final String HEADER_TRANSACTION_ID = "x-transaction-id";
    public static final String HEADER_RPT_ID = "x-rpt-ids";
    public static final String HEADER_NPG_CORRELATION_ID = "x-correlation-id";
    public static final String HEADER_USER_ID = "x-user-id";

    @Override
    public Mono<Void> filter(
                             ServerWebExchange exchange,
                             WebFilterChain chain
    ) {
        final HttpHeaders headers = exchange.getRequest().getHeaders();
        final String transactionId = Optional.ofNullable(headers.get(HEADER_TRANSACTION_ID))
                .orElse(new ArrayList<>())
                .stream()
                .findFirst()
                .orElse(LogTracingUtils.AttributeKeys.CTX_TRANSACTION_ID.getDefaultValue());

        final String rptId = Optional.ofNullable(headers.get(HEADER_RPT_ID))
                .orElse(new ArrayList<>())
                .stream()
                .findFirst()
                .orElse(LogTracingUtils.AttributeKeys.CTX_RPT_IDS.getDefaultValue());

        final String correlationId = Optional.ofNullable(headers.get(HEADER_NPG_CORRELATION_ID))
                .orElse(new ArrayList<>())
                .stream()
                .findFirst()
                .orElse(LogTracingUtils.AttributeKeys.CORRELATION_ID.getDefaultValue());

        final String userId = Optional.ofNullable(headers.get(HEADER_USER_ID))
                .orElse(new ArrayList<>())
                .stream()
                .findFirst()
                .orElse(LogTracingUtils.AttributeKeys.CTX_USER_ID.getDefaultValue());

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
}
