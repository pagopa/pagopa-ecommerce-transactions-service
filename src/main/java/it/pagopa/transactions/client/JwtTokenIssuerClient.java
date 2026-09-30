package it.pagopa.transactions.client;

import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.api.JwtIssuerApi;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenRequestDto;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenResponseDto;
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils;
import it.pagopa.transactions.exceptions.JwtIssuerResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.Map;

@Component
@Slf4j
public class JwtTokenIssuerClient {

    private final JwtIssuerApi jwtIssuerWebClient;

    @Autowired
    public JwtTokenIssuerClient(@Qualifier("jwtIssuerWebClient") JwtIssuerApi jwtIssuerWebClient) {
        this.jwtIssuerWebClient = jwtIssuerWebClient;
    }

    public Mono<CreateTokenResponseDto> createJWTToken(CreateTokenRequestDto createTokenRequestDto) {
        return jwtIssuerWebClient.createJwtToken(createTokenRequestDto)
                .doOnError(
                        WebClientResponseException.class,
                        err -> LogTracingUtils.loggerTracingUtils()
                                .failure()
                                .dependency(LogTracingUtils.JWT_ISSUER_DEPENDENCY)
                                .details(
                                        Map.of(
                                                "status_code",
                                                err.getStatusCode().toString(),
                                                "response_body",
                                                err.getResponseBodyAsString()
                                        )
                                )
                                .logError(log, err, "Received bad response from jwt-issuer-service")
                )
                .doOnSuccess(
                        ignored -> LogTracingUtils.loggerTracingUtils()
                                .success()
                                .dependency(LogTracingUtils.JWT_ISSUER_DEPENDENCY)
                                .logInfo(log, "JWT Token created")
                )
                .onErrorMap(
                        err -> new JwtIssuerResponseException(
                                HttpStatus.BAD_GATEWAY,
                                "Error while invoke method for create jwt token"
                        )
                );
    }
}
