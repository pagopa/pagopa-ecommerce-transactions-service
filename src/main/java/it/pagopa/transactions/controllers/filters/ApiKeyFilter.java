package it.pagopa.transactions.controllers.filters;

import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@Slf4j
public class ApiKeyFilter implements WebFilter {

    private final Set<String> securedPaths;

    private enum ApiKeyType {
        PRIMARY,
        SECONDARY,
        UNKNOWN
    }

    private final Map<String, ApiKeyType> validKeys;

    public ApiKeyFilter(
            @Value("${security.apiKey.securedPaths}") Set<String> securedPaths,
            @Value("${security.apiKey.primary}") String primaryKey,
            @Value("${security.apiKey.secondary}") String secondaryKey
    ) {
        this.securedPaths = securedPaths;
        this.validKeys = Map.of(
                primaryKey,
                ApiKeyType.PRIMARY,
                secondaryKey,
                ApiKeyType.SECONDARY
        );
    }

    @Override
    public @NotNull Mono<Void> filter(
                                      @NotNull ServerWebExchange exchange,
                                      @NotNull WebFilterChain chain
    ) {
        String requestPath = exchange.getRequest().getPath().toString();
        if (securedPaths.stream().anyMatch(requestPath::startsWith)) {
            Optional<String> requestApiKey = getRequestApiKey(exchange);
            boolean isAuthorized = requestApiKey
                    .map(validKeys.keySet()::contains)
                    .orElse(false);
            if (!isAuthorized) {
                LogTracingUtils.loggerTracingUtils()
                        .failure()
                        .details(
                                Map.of(
                                        "request_path",
                                        requestPath
                                )
                        )
                        .logError(log, "Unauthorized request, missing or invalid 'x-api-key' header");
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
            logMatchedApiKeyType(requestApiKey, requestPath);
        }
        return chain.filter(exchange);
    }

    private void logMatchedApiKeyType(
                                      Optional<String> requestApiKey,
                                      String requestPath
    ) {
        ApiKeyType matchedKeyType = requestApiKey.map(validKeys::get).orElse(ApiKeyType.UNKNOWN);
        if (log.isDebugEnabled()) {
            LogTracingUtils.loggerTracingUtils()
                    .success()
                    .details(
                            Map.of(
                                    "matched_key",
                                    matchedKeyType.toString(),
                                    "request_path",
                                    requestPath
                            )
                    )
                    .logDebug(log, "ApiKey matched");
        }
    }

    private Optional<String> getRequestApiKey(ServerWebExchange exchange) {
        return Optional.ofNullable(exchange.getRequest().getHeaders().getFirst("x-api-key"));
    }
}
