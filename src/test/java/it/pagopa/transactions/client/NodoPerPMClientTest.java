package it.pagopa.transactions.client;

import it.pagopa.generated.ecommerce.nodo.v1.ApiClient;
import it.pagopa.generated.ecommerce.nodo.v1.api.NodoApi;
import it.pagopa.generated.ecommerce.nodo.v1.dto.InformazioniPagamentoDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersSpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersUriSpec;
import org.springframework.web.reactive.function.client.WebClient.ResponseSpec;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NodoPerPMClientTest {

    @InjectMocks
    private NodoPerPM nodoPerPM;

    @Mock
    private NodoApi nodoApi;

    @Mock
    private WebClient nodoWebClient;

    @Mock
    private ApiClient nodoApiClient;

    @Mock
    private RequestHeadersSpec requestHeadersSpec;

    @Mock
    private RequestHeadersUriSpec requestHeadersUriSpec;

    @Mock
    private ResponseSpec responseSpec;

    @Test
    void shouldReturnInformazioniPagamentoDtoGivenValidPaymentTokenTest() {

        BigDecimal amount = BigDecimal.valueOf(1200);
        String fiscalCode = "77777777777";
        String paymentNotice = "302000100000009424";
        String paymentToken = UUID.randomUUID().toString();
        String paymentDescription = "paymentDescription";
        String email = "emailTest@emailTest.it";
        String idCarrello = UUID.randomUUID().toString();

        InformazioniPagamentoDto informazioniPagamentoDto = new InformazioniPagamentoDto()
                .codiceFiscale(fiscalCode)
                .oggettoPagamento(paymentNotice)
                .importoTotale(amount)
                .bolloDigitale(false)
                .email(email)
                .idCarrello(idCarrello);

        /**
         * preconditions
         */
        when(nodoApi.getApiClient()).thenReturn(nodoApiClient);
        when(nodoApiClient.getWebClient()).thenReturn(nodoWebClient);
        when(nodoWebClient.get()).thenReturn((RequestHeadersUriSpec) requestHeadersUriSpec);
        when(requestHeadersUriSpec.uri(any(Function.class))).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.header(any(), any())).thenReturn(requestHeadersUriSpec);
        when(requestHeadersUriSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(Predicate.class), any(Function.class))).thenReturn(responseSpec);
        when(responseSpec.bodyToMono(InformazioniPagamentoDto.class)).thenReturn(Mono.just(informazioniPagamentoDto));

        /**
         * test
         */
        InformazioniPagamentoDto testResponse = nodoPerPM.chiediInformazioniPagamento(paymentToken).block();

        /**
         * asserts
         */
        assertThat(testResponse).isEqualTo(informazioniPagamentoDto);
    }

    @Test
    void shouldReturnInformazioniPagamentoError() {
        // FIXME How to test it?
    }

}
