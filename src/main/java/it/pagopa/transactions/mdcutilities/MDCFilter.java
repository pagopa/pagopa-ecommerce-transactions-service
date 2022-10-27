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
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.UriTemplate;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.util.context.Context;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        Map<String,String> transactionMap = getTransactionId(exchange.getRequest());

        //MDC.put("contextKey", getRequestId(request.getHeaders()));
        //Optional.ofNullable(transactionMap.get("transactionId")).ifPresent(v -> MDC.put("transactionId", v));
        //Optional.ofNullable(transactionMap.get("paymentContextCode")).ifPresent(v -> MDC.put("paymentContextCode", v));
        return chain.filter(decorate(exchange))
                .doOnEach(logOnEach(r -> log.info("{} {} {}", request.getMethod(), request.getURI(), request.getBody())))
                .contextWrite(Context.of("contextKey", getRequestId(request.getHeaders())))
                .contextWrite(Context.of("transactionId", transactionMap.getOrDefault("transactionId", "")))
                .contextWrite(Context.of("paymentContextCode", transactionMap.getOrDefault("paymentContextCode", "")));
    }

    private Map<String, String> getTransactionId(ServerHttpRequest request) {
        UriTemplate uriTemplatePCC = new UriTemplate("/transactions/payment-context-codes/{paymentContextCode}/activation-results");
        UriTemplate uriTemplateStandard = new UriTemplate("/transactions/{transactionId}");
        return uriTemplatePCC.matches(request.getPath().value()) ? uriTemplatePCC.match(request.getPath().value()) : uriTemplateStandard.match(request.getPath().value());
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