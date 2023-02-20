package it.pagopa.transactions.mdcutilities;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriTemplate;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.util.context.Context;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Component
@Slf4j
public class MDCFilter implements WebFilter {

    public static final String CONTEXT_KEY = "contextKey";
    public static final String TRANSACTION_ID = "transactionId";
    public static final String RPT_ID = "rptId";

    private ServerWebExchange decorate(ServerWebExchange exchange) {

        final ServerHttpRequest decoratedRequest = new MDCCachingValuesServerHttpRequestDecorator(
                exchange.getRequest()
        );

        return new ServerWebExchangeDecorator(exchange) {

            @Override
            public ServerHttpRequest getRequest() {
                return decoratedRequest;
            }

        };
    }

    @Override
    public Mono<Void> filter(
                             ServerWebExchange exchange,
                             WebFilterChain chain
    ) {
        ServerHttpRequest request = exchange.getRequest();
        Map<String, String> transactionMap = getTransactionId(exchange.getRequest());

        ServerWebExchange serverWebExchange = decorate(exchange);
        return chain.filter(serverWebExchange)
                .doOnEach(logOnEach(r -> {
                    log.debug(
                            "{} {} {}",
                            request.getMethod(),
                            request.getURI(),
                            ((MDCCachingValuesServerHttpRequestDecorator) serverWebExchange.getRequest())
                                    .getInfoFromValuesMap()
                    );
                    ((MDCCachingValuesServerHttpRequestDecorator) serverWebExchange.getRequest()).getObjectAsMap()
                            .entrySet().stream().filter(entry -> entry.getKey().equals(TRANSACTION_ID) || entry.getKey().equals(RPT_ID))
                            .forEach((entry) -> transactionMap.put(entry.getKey(), entry.getValue().toString())
                            );
                }))
                .contextWrite(Context.of(CONTEXT_KEY, UUID.randomUUID().toString()))
                .contextWrite(Context.of(TRANSACTION_ID, transactionMap.getOrDefault(TRANSACTION_ID, "")))
                .contextWrite(Context.of(RPT_ID, transactionMap.getOrDefault(RPT_ID, "")));
    }

    private Map<String, String> getTransactionId(ServerHttpRequest request) {
        UriTemplate uriTemplateStandard = new UriTemplate("/transactions/{transactionId}");
        return uriTemplateStandard.match(request.getPath().value());
    }

    public static <T> Consumer<Signal<T>> logOnEach(Consumer<T> logStatement) {
        return signal -> {
            String contextValue = signal.getContextView().getOrDefault(CONTEXT_KEY, "");
            String rptId = signal.getContextView().getOrDefault(RPT_ID, "");
            String transactionId = signal.getContextView().getOrDefault(TRANSACTION_ID, "");
            try (
                    MDC.MDCCloseable mdcCloseable1 = MDC.putCloseable(CONTEXT_KEY, contextValue);
                    MDC.MDCCloseable mdcCloseable2 = MDC.putCloseable(RPT_ID, rptId);
                    MDC.MDCCloseable mdcCloseable4 = MDC.putCloseable(TRANSACTION_ID, transactionId);) {
                logStatement.accept(signal.get());
            }
        };
    }

}
