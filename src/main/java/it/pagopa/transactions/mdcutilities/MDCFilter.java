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

import java.util.Map;
import java.util.UUID;

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
        Map<String,String> transactionMap = getTransactionId(exchange.getRequest());
        MDC.put("diagnosticContextKey", UUID.randomUUID().toString());
        MDC.put("transactionId", transactionMap.getOrDefault("transactionId",""));
        MDC.put("paymentContextCode", transactionMap.getOrDefault("paymentContextCode",""));
        return chain.filter(decorate(exchange));
    }

    private Map<String, String> getTransactionId(ServerHttpRequest request) {
        UriTemplate uriTemplatePCC = new UriTemplate("/transactions/payment-context-codes/{paymentContextCode}/activation-results");
        UriTemplate uriTemplateStandard = new UriTemplate("/transactions/{transactionId}");
        return uriTemplatePCC.matches(request.getPath().value()) ? uriTemplatePCC.match(request.getPath().value()) : uriTemplateStandard.match(request.getPath().value());
    }

}