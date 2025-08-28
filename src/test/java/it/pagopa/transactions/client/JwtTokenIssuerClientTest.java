package it.pagopa.transactions.client;

import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.api.JwtIssuerApi;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenRequestDto;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.dto.CreateTokenResponseDto;
import it.pagopa.transactions.exceptions.JwtIssuerResponseException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(SpringExtension.class)
@SpringBootTest
@TestPropertySource(locations = "classpath:application-tests.properties")
class JwtTokenIssuerClientTest {

    private JwtTokenIssuerClient client;
    @Autowired
    private JwtIssuerApi realJwtIssuerApi;
    @Value("${jwtissuer.apiKey}")
    private String apiKey;
    @Mock
    JwtIssuerApi jwtIssuerApi;

    @BeforeEach
    void init() {
        client = new JwtTokenIssuerClient(jwtIssuerApi);
        Hooks.onOperatorDebug();
    }

    @Test
    void shouldReturnJwtTokenResponse() {

        Map<String, String> privateClaims = new HashMap<>();
        privateClaims.put("claim1", "value1");
        privateClaims.put("claim2", "value2");
        CreateTokenRequestDto createTokenRequestDto = new CreateTokenRequestDto().audience("audience").duration(1000)
                .privateClaims(privateClaims);
        CreateTokenResponseDto createTokenResponseDto = new CreateTokenResponseDto().token("token");
        Mockito.when(jwtIssuerApi.createJwtToken(createTokenRequestDto))
                .thenReturn(Mono.just(createTokenResponseDto));

        StepVerifier.create(client.createJWTToken(createTokenRequestDto))
                .expectNext(createTokenResponseDto);

        verify(jwtIssuerApi, times(1)).createJwtToken(createTokenRequestDto);
    }

    @Test
    void shouldAddApiKeyHeaderWhenMakingHttpRequest() throws IOException, InterruptedException {
        CreateTokenResponseDto createTokenResponseDto = new CreateTokenResponseDto().token("token");
        JwtTokenIssuerClient jwtTokenIssuerClient = new JwtTokenIssuerClient(realJwtIssuerApi);
        MockWebServer mockWebServer = new MockWebServer();
        mockWebServer.start(8080);

        mockWebServer.enqueue(
                new MockResponse()
                        .setResponseCode(201)
                        .setBody("{\"token\":\"token\"}")
                        .addHeader("Content-Type", "application/json")
        );

        // test and assertions
        StepVerifier.create(
                jwtTokenIssuerClient.createJWTToken(
                        new CreateTokenRequestDto()
                                .duration(100)
                                .audience("test")
                )
        )
                .expectNext(createTokenResponseDto)
                .verifyComplete();

        RecordedRequest request = mockWebServer.takeRequest();

        // Assert the x-api-key header
        String apiKeyHeader = request.getHeader("x-api-key");
        assertEquals(apiKey, apiKeyHeader);

        mockWebServer.shutdown();
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
                        JwtIssuerResponseException.class::isInstance
                )
                .verify();
    }

}
