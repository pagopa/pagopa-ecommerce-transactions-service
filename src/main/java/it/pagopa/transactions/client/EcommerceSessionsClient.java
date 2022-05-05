package it.pagopa.transactions.client;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import it.pagopa.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.ecommerce.sessions.v1.dto.SessionTokenDto;
import reactor.core.publisher.Mono;

@Component
public class EcommerceSessionsClient {

    @Autowired
    @Qualifier("ecommerceSessionsWebClient")
    private WebClient ecommerceSessionsWebClient;

    public Mono<SessionTokenDto> createSessionToken(SessionDataDto request) {

        return ecommerceSessionsWebClient.post().body(Mono.just(request), SessionDataDto.class)
                .retrieve()
                .onStatus(HttpStatus::isError,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(errorResponseBody -> Mono.error(
                                        new ResponseStatusException(clientResponse.statusCode(), errorResponseBody))))
                .bodyToMono(SessionTokenDto.class);
    }
}
