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
    public static final String PAYMENT_CONTEXT_CODE = "paymentContextCode";
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
        return Mono.just(null);
    }

    private Map<String, String> getTransactionId(ServerHttpRequest request) {
        UriTemplate uriTemplatePCC = new UriTemplate(
                "/transactions/payment-context-codes/{paymentContextCode}/activation-results"
        );
        UriTemplate uriTemplateStandard = new UriTemplate("/transactions/{transactionId}");
        return uriTemplatePCC.matches(request.getPath().value()) ? uriTemplatePCC.match(request.getPath().value())
                : uriTemplateStandard.match(request.getPath().value());
    }

    public static <T> Consumer<Signal<T>> logOnEach(Consumer<T> logStatement) {
        return signal -> {
            String contextValue = signal.getContextView().getOrDefault(CONTEXT_KEY, "");
            String rptId = signal.getContextView().getOrDefault(RPT_ID, "");
            String paymentContextCode = signal.getContextView().getOrDefault(PAYMENT_CONTEXT_CODE, "");
            String transactionId = signal.getContextView().getOrDefault(TRANSACTION_ID, "");
            try (
                    MDC.MDCCloseable mdcCloseable1 = MDC.putCloseable(CONTEXT_KEY, contextValue);
                    MDC.MDCCloseable mdcCloseable2 = MDC.putCloseable(RPT_ID, rptId);
                    MDC.MDCCloseable mdcCloseable3 = MDC.putCloseable(PAYMENT_CONTEXT_CODE, paymentContextCode);
                    MDC.MDCCloseable mdcCloseable4 = MDC.putCloseable(TRANSACTION_ID, transactionId);) {
                logStatement.accept(signal.get());
            }
        };
    }

}
