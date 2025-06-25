package it.pagopa.transactions.controllers.filters;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.RequestPath;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ApiKeyFilterTest {
    private static final Set<String> securedPaths = Set.of("/test1", "/test2");
    private static final String PRIMARY_KEY = "primaryKey";
    private static final String SECONDARY_KEY = "secondaryKey";
    private final ServerWebExchange exchange = Mockito.mock(ServerWebExchange.class);
    private final WebFilterChain webFilterChain = Mockito.mock(WebFilterChain.class);
    private final ServerHttpRequest serverHttpRequest = Mockito.mock(ServerHttpRequest.class);
    private final RequestPath requestPath = Mockito.mock(RequestPath.class);
    private final HttpHeaders httpHeaders = Mockito.mock(HttpHeaders.class);
    private final ServerHttpResponse serverHttpResponse = Mockito.mock(ServerHttpResponse.class);
    private final ApiKeyFilter apiKeyFilter = new ApiKeyFilter(
            securedPaths,
            PRIMARY_KEY,
            SECONDARY_KEY
    );

    public static Stream<Arguments> validApiKeysAndPaths() {
        List<Arguments> arguments = new ArrayList<>();
        for (String path : securedPaths) {
            arguments.add(Arguments.of(PRIMARY_KEY, path));
            arguments.add(Arguments.of(SECONDARY_KEY, path));
        }
        return arguments.stream();
    }

    @ParameterizedTest
    @MethodSource("validApiKeysAndPaths")
    void shouldAllowRequestWithValidApiKeyForValidPaths(
                                                        String apiKey,
                                                        String path
    ) {
        // pre-condition
        given(exchange.getRequest()).willReturn(serverHttpRequest);
        given(serverHttpRequest.getPath()).willReturn(requestPath);
        given(requestPath.toString()).willReturn(path);
        given(serverHttpRequest.getHeaders()).willReturn(httpHeaders);
        given(httpHeaders.getFirst(any())).willReturn(apiKey);
        given(webFilterChain.filter(exchange)).willReturn(Mono.empty());
        // test
        StepVerifier.create(apiKeyFilter.filter(exchange, webFilterChain))
                .expectNext()
                .verifyComplete();
        // assertions
        verify(exchange, times(2)).getRequest();
        verify(serverHttpRequest, times(1)).getPath();
        verify(serverHttpRequest, times(1)).getHeaders();
        verify(httpHeaders, times(1)).getFirst("x-api-key");
        verify(webFilterChain, times(1)).filter(exchange);
    }

    @Test
    void shouldRejectRequestsForInvalidApiKeys() {
        // pre-condition
        String invalidApiKey = "invalidApiKey";
        given(exchange.getRequest()).willReturn(serverHttpRequest);
        given(serverHttpRequest.getPath()).willReturn(requestPath);
        given(requestPath.toString()).willReturn(securedPaths.stream().toList().get(0));
        given(serverHttpRequest.getHeaders()).willReturn(httpHeaders);
        given(httpHeaders.getFirst(any())).willReturn(invalidApiKey);
        given(webFilterChain.filter(exchange)).willReturn(Mono.empty());
        given(exchange.getResponse()).willReturn(serverHttpResponse);
        given(serverHttpResponse.setComplete()).willReturn(Mono.empty());
        // test
        StepVerifier.create(apiKeyFilter.filter(exchange, webFilterChain))
                .expectNext()
                .verifyComplete();
        // assertions
        verify(exchange, times(2)).getRequest();
        verify(serverHttpRequest, times(1)).getPath();
        verify(serverHttpRequest, times(1)).getHeaders();
        verify(httpHeaders, times(1)).getFirst("x-api-key");
        verify(webFilterChain, times(0)).filter(exchange);
        verify(serverHttpResponse, times(1)).setStatusCode(HttpStatus.UNAUTHORIZED);
        verify(serverHttpResponse, times(1)).setComplete();
    }

    @ParameterizedTest
    @MethodSource("validApiKeysAndPaths")
    void shouldSkipApiKeyValidationForNotConfiguredPaths(
                                                         String apiKey,
                                                         String path
    ) {
        // pre-condition
        String invalidPath = "/invalidPath" + path;
        given(exchange.getRequest()).willReturn(serverHttpRequest);
        given(serverHttpRequest.getPath()).willReturn(requestPath);
        given(requestPath.toString()).willReturn(invalidPath);
        given(serverHttpRequest.getHeaders()).willReturn(httpHeaders);
        given(httpHeaders.getFirst(any())).willReturn(apiKey);
        given(webFilterChain.filter(exchange)).willReturn(Mono.empty());
        given(exchange.getResponse()).willReturn(serverHttpResponse);
        given(serverHttpResponse.setComplete()).willReturn(Mono.empty());
        // test
        StepVerifier.create(apiKeyFilter.filter(exchange, webFilterChain))
                .expectNext()
                .verifyComplete();
        // assertions
        verify(exchange, times(1)).getRequest();
        verify(serverHttpRequest, times(1)).getPath();
        verify(serverHttpRequest, times(0)).getHeaders();
        verify(httpHeaders, times(0)).getFirst("x-api-key");
        verify(webFilterChain, times(1)).filter(exchange);
        verify(serverHttpResponse, times(0)).setStatusCode(HttpStatus.UNAUTHORIZED);
        verify(serverHttpResponse, times(0)).setComplete();
    }

}
