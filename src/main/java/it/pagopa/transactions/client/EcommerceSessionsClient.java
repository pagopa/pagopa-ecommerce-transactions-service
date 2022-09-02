package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.sessions.v1.api.DefaultApi;
import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionRequestDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Component
public class EcommerceSessionsClient {

    @Autowired
    @Qualifier("ecommerceSessionsWebClient")
    private DefaultApi ecommerceSessionsWebClient;

    public Mono<SessionDataDto> createSessionToken(SessionRequestDto request) {

        return ecommerceSessionsWebClient
                .getApiClient()
                .getWebClient()
                .post()
                .body(Mono.just(request), SessionRequestDto.class)
                .retrieve()
                .onStatus(HttpStatus::isError,
                        clientResponse -> clientResponse.bodyToMono(String.class)
                                .flatMap(errorResponseBody -> Mono.error(
                                        new ResponseStatusException(clientResponse.statusCode(), errorResponseBody))))
                .bodyToMono(SessionDataDto.class);
    }
}
