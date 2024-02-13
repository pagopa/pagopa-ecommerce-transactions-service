package it.pagopa.transactions.mdcutilities;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

@Component
@Slf4j
public class MDCFilter implements WebFilter {

    public static final String CONTEXT_KEY = "contextKey";
    public static final String HEADER_TRANSACTION_ID = "x-transaction-id";
    public static final String HEADER_RPT_ID = "x-rpt-id";
    public static final String HEADER_NPG_CORRELATION_ID = "x-correlation-id";

    @Override
    public Mono<Void> filter(
                             ServerWebExchange exchange,
                             WebFilterChain chain
    ) {
        final HttpHeaders headers = exchange.getRequest().getHeaders();
        final String transactionId = Optional.ofNullable(headers.get(HEADER_TRANSACTION_ID)).orElse(new ArrayList<>())
                .stream()
                .findFirst().orElse(TransactionTracingUtils.TracingEntry.TRANSACTION_ID.getDefaultValue());
        final String rptId = Optional.ofNullable(headers.get(HEADER_RPT_ID)).orElse(new ArrayList<>()).stream()
                .findFirst().orElse(TransactionTracingUtils.TracingEntry.RPT_IDS.getDefaultValue());
        final String correlationId = Optional.ofNullable(headers.get(HEADER_NPG_CORRELATION_ID))
                .orElse(new ArrayList<>()).stream()
                .findFirst().orElse(TransactionTracingUtils.TracingEntry.CORRELATION_ID.getDefaultValue());

        return chain.filter(exchange)
                .contextWrite(Context.of(CONTEXT_KEY, UUID.randomUUID().toString()))
                .contextWrite(Context.of(TransactionTracingUtils.TracingEntry.TRANSACTION_ID.getKey(), transactionId))
                .contextWrite(Context.of(TransactionTracingUtils.TracingEntry.RPT_IDS.getKey(), rptId))
                .contextWrite(Context.of(TransactionTracingUtils.TracingEntry.CORRELATION_ID.getKey(), correlationId));
    }
}
