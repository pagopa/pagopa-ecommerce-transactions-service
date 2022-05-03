package it.pagopa.transactions.client;

import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import it.pagopa.ecommerce.sessions.v1.api.DefaultApi;
import it.pagopa.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.ecommerce.sessions.v1.dto.SessionTokenDto;
import reactor.core.publisher.Mono;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class EcommerceSessionsClientTest {

    @InjectMocks
    private EcommerceSessionsClient client;

    @Mock
    private DefaultApi ecommerceSessionsWebClient;

    @Test
    void shouldReturnValidTokenTest() {

        SessionTokenDto sessionToken = new SessionTokenDto();
        sessionToken.setToken(UUID.randomUUID().toString());
        Mono<SessionTokenDto> response = Mono.just(sessionToken);
        SessionDataDto request = new SessionDataDto();
        request.setEmail("test@mail.it");
        request.setPaymentToken(UUID.randomUUID().toString());

        /**
         * preconditions
         */
        when(ecommerceSessionsWebClient.postToken(request)).thenReturn(response);

        /**
         * test
         */
        SessionTokenDto testResponse = client.createSessionToken(request).block();

        /**
         * asserts
         */
        assertThat(testResponse.getToken()).isEqualTo(testResponse.getToken());
    }

}
