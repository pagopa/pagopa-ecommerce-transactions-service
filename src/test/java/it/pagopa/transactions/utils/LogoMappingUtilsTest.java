package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.CardAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.CardsAuthRequestDetailsDto;
import it.pagopa.generated.transactions.server.model.PostePayAuthRequestDetailsDto;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.configurations.BrandLogoConfig;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LogoMappingUtilsTest {

    private final URI VISA_LOGO_URI = URI.create("http://visa");
    private final URI UNKNOWN_LOGO_URI = URI.create("http://unknown");
    private final Map<CardAuthRequestDetailsDto.BrandEnum, URI> pgsBrandConfig = Map.of(
            CardAuthRequestDetailsDto.BrandEnum.UNKNOWN,
            UNKNOWN_LOGO_URI,
            CardAuthRequestDetailsDto.BrandEnum.VISA,
            VISA_LOGO_URI
    );

    private final Map<String, URI> npgPaymentCircuitLogoMap = Map.of(
            BrandLogoConfig.UNKNOWN_LOGO_KEY,
            UNKNOWN_LOGO_URI,
            "VISA",
            VISA_LOGO_URI
    );

    private final LogoMappingUtils logoMappingUtils = new LogoMappingUtils(pgsBrandConfig, npgPaymentCircuitLogoMap);

    @Test
    void shouldGetLogoForPgsAuthRequest() {
        // pre-conditions
        AuthorizationRequestData authorizationRequestData = new AuthorizationRequestData(
                new TransactionId(TransactionTestUtils.TRANSACTION_ID),
                List.of(),
                new Confidential<>(""),
                0,
                "paymentInstrumentId",
                "pspId",
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "PGS",
                Optional.empty(),
                Optional.empty(),
                "VISA",
                new CardAuthRequestDetailsDto()
                        .brand(CardAuthRequestDetailsDto.BrandEnum.VISA)
                        .detailType("card")
        );
        // test
        URI logo = logoMappingUtils.getLogo(authorizationRequestData);
        // assertions
        assertEquals(VISA_LOGO_URI, logo);
    }

    @Test
    void shouldGetLogoForNpgAuthRequest() {
        // pre-conditions
        AuthorizationRequestData authorizationRequestData = new AuthorizationRequestData(
                new TransactionId(TransactionTestUtils.TRANSACTION_ID),
                List.of(),
                new Confidential<>(""),
                0,
                "paymentInstrumentId",
                "pspId",
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "PGS",
                Optional.empty(),
                Optional.empty(),
                "VISA",
                new CardsAuthRequestDetailsDto()
                        .orderId("orderId")
        );
        // test
        URI logo = logoMappingUtils.getLogo(authorizationRequestData);
        // assertions
        assertEquals(VISA_LOGO_URI, logo);
    }

    @Test
    void shouldGetUnknownLogoForNpgAuthRequestForUnknownReceivedBrand() {
        // pre-conditions
        AuthorizationRequestData authorizationRequestData = new AuthorizationRequestData(
                new TransactionId(TransactionTestUtils.TRANSACTION_ID),
                List.of(),
                new Confidential<>(""),
                0,
                "paymentInstrumentId",
                "pspId",
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "PGS",
                Optional.empty(),
                Optional.empty(),
                "UnhandledBrand",
                new CardsAuthRequestDetailsDto()
                        .orderId("orderId")
        );
        // test
        URI logo = logoMappingUtils.getLogo(authorizationRequestData);
        // assertions
        assertEquals(UNKNOWN_LOGO_URI, logo);
    }

    @Test
    void shouldThrowInvalidRequestExceptionForUnhandledAuthRequestDetails() {
        // pre-conditions
        AuthorizationRequestData authorizationRequestData = new AuthorizationRequestData(
                new TransactionId(TransactionTestUtils.TRANSACTION_ID),
                List.of(),
                new Confidential<>(""),
                0,
                "paymentInstrumentId",
                "pspId",
                "CP",
                "brokerName",
                "pspChannelCode",
                "paymentMethodName",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                "PGS",
                Optional.empty(),
                Optional.empty(),
                "UnhandledBrand",
                new PostePayAuthRequestDetailsDto()
        );
        // assertions
        assertThrows(InvalidRequestException.class, () -> logoMappingUtils.getLogo(authorizationRequestData));
    }

}
