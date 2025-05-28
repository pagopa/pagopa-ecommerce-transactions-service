package it.pagopa.transactions.client;

import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.api.JwtIssuerApi;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenRequestDto;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenResponseDto;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.JWKResponseDto;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.JWKSResponseDto;
import it.pagopa.transactions.exceptions.BadGatewayException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(SpringExtension.class)
public class JwtTokenIssuerClientTest {

    private JwtTokenIssuerClient client;

    @Mock
    JwtIssuerApi jwtIssuerApi;

    @BeforeEach
    public void init() {
        client = new JwtTokenIssuerClient(jwtIssuerApi);
        Hooks.onOperatorDebug();
    }

    @Test
    void shouldReturnKeys() {

        JWKResponseDto jwkResponseDto = new JWKResponseDto();
        jwkResponseDto.kty(JWKResponseDto.KtyEnum.RSA);
        jwkResponseDto.kid("kid");
        jwkResponseDto.n("n");
        jwkResponseDto.e("e");
        jwkResponseDto.use("use");
        jwkResponseDto.alg("alg");

        JWKResponseDto jwkResponseDto2 = new JWKResponseDto();
        jwkResponseDto2.kty(JWKResponseDto.KtyEnum.RSA);
        jwkResponseDto2.kid("kid2");
        jwkResponseDto2.n("n2");
        jwkResponseDto2.e("e2");
        jwkResponseDto2.use("use2");
        jwkResponseDto2.alg("alg2");

        JWKSResponseDto jwksResponseDto = new JWKSResponseDto();
        jwksResponseDto.addKeysItem(jwkResponseDto);
        jwksResponseDto.addKeysItem(jwkResponseDto2);
        Mockito.when(jwtIssuerApi.getTokenPublicKeys()).thenReturn(Mono.just(jwksResponseDto));

        StepVerifier.create(client.getKeys())
                .expectNext(jwksResponseDto);

        verify(jwtIssuerApi, times(1)).getTokenPublicKeys();
    }

    @Test
    void getKeysShouldThrowException() {

        Mockito.when(jwtIssuerApi.getTokenPublicKeys()).thenReturn(
                Mono.error(
                        new WebClientResponseException(
                                "error",
                                HttpStatus.BAD_REQUEST.value(),
                                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                                null,
                                null,
                                null
                        )
                )
        );

        StepVerifier.create(client.getKeys())
                .expectErrorMatches(
                        error -> error instanceof BadGatewayException
                )
                .verify();
    }

    @Test
    void shouldReturnJwtTokenResponse() {

        Map<String, String> privateClaims = new HashMap<>();
        privateClaims.put("claim1", "value1");
        privateClaims.put("claim2", "value2");
        CreateTokenRequestDto createTokenRequestDto = new CreateTokenRequestDto().audience("audience").duration(1000)
                .privateClaims(privateClaims);
        CreateTokenResponseDto createTokenResponseDto = new CreateTokenResponseDto().token("token");
        Mockito.when(jwtIssuerApi.createJwtToken(eq(createTokenRequestDto)))
                .thenReturn(Mono.just(createTokenResponseDto));

        StepVerifier.create(client.createJWTToken(createTokenRequestDto))
                .expectNext(createTokenResponseDto);

        verify(jwtIssuerApi, times(1)).createJwtToken(createTokenRequestDto);
    }

    @Test
    void createTokenShouldThrowException() {

        Mockito.when(jwtIssuerApi.createJwtToken(any()))
                .thenReturn(
                        Mono.error(
                                new WebClientResponseException(
                                        "error",
                                        HttpStatus.BAD_REQUEST.value(),
                                        HttpStatus.BAD_REQUEST.getReasonPhrase(),
                                        null,
                                        null,
                                        null
                                )
                        )
                );

        StepVerifier.create(client.createJWTToken(any()))
                .expectErrorMatches(
                        error -> error instanceof BadGatewayException
                )
                .verify();
    }

}
