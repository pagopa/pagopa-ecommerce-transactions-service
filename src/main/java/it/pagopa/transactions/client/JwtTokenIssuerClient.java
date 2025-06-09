package it.pagopa.transactions.client;

import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.api.JwtIssuerApi;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenRequestDto;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenResponseDto;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.JWKSResponseDto;
import it.pagopa.transactions.exceptions.BadGatewayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class JwtTokenIssuerClient {

    private final JwtIssuerApi jwtIssuerWebClient;

    @Autowired
    public JwtTokenIssuerClient(@Qualifier("jwtIssuerWebClient") JwtIssuerApi jwtIssuerWebClient) {
        this.jwtIssuerWebClient = jwtIssuerWebClient;
    }

    public Mono<CreateTokenResponseDto> createJWTToken(CreateTokenRequestDto createTokenRequestDto) {
        return jwtIssuerWebClient.createJwtToken(createTokenRequestDto).doOnError(
                WebClientResponseException.class,
                JwtTokenIssuerClient::logWebClientException
        )
                .onErrorMap(
                        err -> new BadGatewayException(
                                "Error while invoke method for create jwt token",
                                HttpStatus.BAD_GATEWAY
                        )
                );
    }

    private static void logWebClientException(WebClientResponseException e) {
        log.info(
                "Got bad response from wallet-service [HTTP {}]: {}",
                e.getStatusCode(),
                e.getResponseBodyAsString()
        );
    }
}
