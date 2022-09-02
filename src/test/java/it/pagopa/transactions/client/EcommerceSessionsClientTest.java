package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.sessions.v1.ApiClient;
import it.pagopa.generated.ecommerce.sessions.v1.api.DefaultApi;
import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionRequestDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EcommerceSessionsClientTest {

    @InjectMocks
    private EcommerceSessionsClient client;

    @Mock
    private WebClient ecommerceSessionsWebClient;
    @Mock
    private WebClient.RequestBodyUriSpec mockRequestBodyUriSpec;
    @Mock
    private WebClient.RequestHeadersSpec mockRequestHeadersSpec;
    @Mock
    private WebClient.ResponseSpec mockResponseSpec;

    @Mock
    private DefaultApi ecommerceSessionsDefaultApi;

    @Mock
    private ApiClient apiClient;

    @Test
    void shouldReturnValidTokenTest() {

        String TEST_TOKEN = UUID.randomUUID().toString();
        String TEST_EMAIL = "test@mail.it";
        String TEST_RPTID = "77777777777302016723749670035";

        SessionRequestDto request = new SessionRequestDto();
        request.setEmail(TEST_EMAIL);
        request.setPaymentToken(TEST_TOKEN);
        request.setRptId(TEST_RPTID);

        SessionRequestDto dataDto = new SessionRequestDto();
        dataDto.setEmail(TEST_EMAIL);
        dataDto.setRptId(TEST_RPTID);
        Mono<SessionRequestDto> dataDtoMono = Mono.just(dataDto);

        SessionDataDto tokenDto = new SessionDataDto();
        tokenDto.setSessionToken(TEST_TOKEN);
        tokenDto.setEmail(TEST_EMAIL);
        tokenDto.setRptId(TEST_RPTID);

        /**
         * preconditions
         */
        when(ecommerceSessionsWebClient.post()).thenReturn(mockRequestBodyUriSpec);
        when(mockRequestBodyUriSpec.body(Mockito.any(), Mockito.eq(SessionDataDto.class))).thenReturn(mockRequestHeadersSpec);
        when(mockRequestHeadersSpec.retrieve()).thenReturn(mockResponseSpec);
        when(mockResponseSpec.onStatus(Mockito.any(), Mockito.any())).thenReturn(mockResponseSpec);
        when(mockResponseSpec.bodyToMono(SessionDataDto.class)).thenReturn(Mono.just(tokenDto));
        when(ecommerceSessionsDefaultApi.getApiClient()).thenReturn(apiClient);
        when(apiClient.getWebClient()).thenReturn(ecommerceSessionsWebClient);


        /**
         * test
         */
        SessionDataDto testResponse = client.createSessionToken(dataDto).block();

        /**
         * asserts
         */
        assertThat(testResponse.getPaymentToken()).isEqualTo(testResponse.getPaymentToken());
    }

}
