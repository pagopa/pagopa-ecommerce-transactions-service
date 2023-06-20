package it.pagopa.transactions.configurations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.pagopa.generated.ecommerce.nodo.v2.dto.AdditionalPaymentInformationsDto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.ecommerce.paymentmethods.v1.api.PaymentMethodsApi;
import it.pagopa.generated.ecommerce.paymentmethods.v1.auth.ApiKeyAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class WebClientsConfigTest {

    private final WebClientsConfig webClientsConfig = new WebClientsConfig();

    @Test
    void shouldCorrectlySerialize() {
        // Precondition
        AdditionalPaymentInformationsDto additionalPaymentInformationsDto = new AdditionalPaymentInformationsDto()
                .outcomePaymentGateway(AdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum.OK)
                .totalAmount(new BigDecimal((101)).toString())
                .rrn("rrn")
                .fee(new BigDecimal(1).toString())
                .timestampOperation(
                        OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                );

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(List.of("paymentToken"))
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP("identificativoPsp")
                .idBrokerPSP("identificativoIntermediario")
                .idChannel("identificativoCanale")
                .transactionId("transactionId")
                .fee(new BigDecimal(1))
                .timestampOperation(OffsetDateTime.now())
                .totalAmount(new BigDecimal(101))
                .additionalPaymentInformations(additionalPaymentInformationsDto);

        ObjectMapper mapper = webClientsConfig.getNodeObjectMapper();

        // Test
        try {
            String jsonRequest = mapper.writeValueAsString(closePaymentRequest);

            // Asserts
            assertThat(jsonRequest).contains("\"rrn\":\"rrn\"");

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldSerializeWithoutNullValue() {
        // Precondition
        AdditionalPaymentInformationsDto additionalPaymentInformationsDto = new AdditionalPaymentInformationsDto()
                .outcomePaymentGateway(AdditionalPaymentInformationsDto.OutcomePaymentGatewayEnum.OK)
                .totalAmount(new BigDecimal((101)).toString())
                .rrn(null)
                .fee(new BigDecimal(1).toString())
                .timestampOperation(
                        OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS)
                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                );

        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                .paymentTokens(List.of("paymentToken"))
                .outcome(ClosePaymentRequestV2Dto.OutcomeEnum.OK)
                .idPSP("identificativoPsp")
                .idBrokerPSP("identificativoIntermediario")
                .idChannel("identificativoCanale")
                .transactionId("transactionId")
                .fee(new BigDecimal(1))
                .timestampOperation(OffsetDateTime.now())
                .totalAmount(new BigDecimal(101))
                .additionalPaymentInformations(additionalPaymentInformationsDto);

        ObjectMapper mapper = webClientsConfig.getNodeObjectMapper();

        // Test
        try {
            String jsonRequest = mapper.writeValueAsString(closePaymentRequest);

            // Asserts
            assertThat(jsonRequest).doesNotContain("rrn");

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void shouldValueApiKeyForEcommercePaymentMethodClient() {
        // pre-conditions
        String basePath = "http://paymentMethod/base/path";
        String apiKey = "paymentMethodsApiKey";
        // test
        PaymentMethodsApi paymentMethodsApi = webClientsConfig
                .ecommercePaymentInstrumentsWebClient(basePath, 1000, 1000, apiKey);
        // assertions
        assertEquals(basePath, paymentMethodsApi.getApiClient().getBasePath());
        ApiKeyAuth apiKeyAuth = (ApiKeyAuth) paymentMethodsApi.getApiClient().getAuthentication("ApiKeyAuth");
        assertEquals(apiKey, apiKeyAuth.getApiKey());
    }

}
