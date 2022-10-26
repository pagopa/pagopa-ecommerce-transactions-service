package it.pagopa.transactions.mdcutilities;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.UriTemplate;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Component
@Slf4j
public class MDCFilter implements WebFilter {

    private ServerWebExchange decorate(ServerWebExchange exchange) {

        final ServerHttpRequest decoratedRequest = new MDCCachingValuesServerHttpRequestDecorator(exchange.getRequest());

        return new ServerWebExchangeDecorator(exchange) {

            @Override
            public ServerHttpRequest getRequest() {
                return decoratedRequest;
            }

        };
    }


    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String requestId = getRequestId(request.getHeaders());
        Map<String,String> transactionMap = getTransactionId(exchange.getRequest());
        MDC.put("CONTEXT_KEY", requestId);
        MDC.put("TRANSACTION_ID", transactionMap.getOrDefault("transactionId",""));
        return chain.filter(decorate(exchange));
    }

    private Map<String, String> getTransactionId(ServerHttpRequest request) {
        return new UriTemplate("/transactions/{transactionId}").match(request.getPath().value());
    }

    private String getRequestId(HttpHeaders headers) {
        List<String> requestIdHeaders = headers.get("X-Request-ID");
        return requestIdHeaders == null || requestIdHeaders.isEmpty()
                ? UUID.randomUUID().toString()
                : requestIdHeaders.get(0);
    }

    public static <T> Consumer<Signal<T>> logOnEach(Consumer<T> logStatement) {
        return signal -> {
            String contextValue = signal.getContextView().get("CONTEXT_KEY");
            try (MDC.MDCCloseable cMdc = MDC.putCloseable("MDC_KEY", contextValue)) {
                logStatement.accept(signal.get());
            }
        };
    }

    public static <T> Consumer<Signal<T>> logOnNext(Consumer<T> logStatement) {
        return signal -> {
            if (!signal.isOnNext()) return;
            String contextValue = signal.getContextView().get("CONTEXT_KEY");
            try (MDC.MDCCloseable cMdc = MDC.putCloseable("MDC_KEY", contextValue)) {
                logStatement.accept(signal.get());
            }
        };
    }


}