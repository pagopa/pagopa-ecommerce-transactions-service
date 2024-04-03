package it.pagopa.transactions.configurations;

import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.client.NpgClient;
import it.pagopa.ecommerce.commons.exceptions.NpgApiKeyConfigurationException;
import it.pagopa.ecommerce.commons.utils.NpgApiKeyHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class NpgPspApiKeysConfigTest {

    private final NpgPspApiKeysConfigBuilder npgPspApiKeysConfig = new NpgPspApiKeysConfigBuilder();

    private final String pspConfigurationJson = """
            {
                "psp1" : "key-psp1",
                "psp2" : "key-psp2",
                "psp3" : "key-psp3"
            }
            """;

    private final Set<String> pspToHandle = Set.of("psp1", "psp2", "psp3");

    @ParameterizedTest
    @ValueSource(
            strings = {
                    "psp1",
                    "psp2",
                    "psp3"
            }
    )
    void shouldParsePspConfigurationSuccessfully(String pspId) {
        it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig pspConfiguration = npgPspApiKeysConfig
                .npgCardsApiKeys(pspConfigurationJson, new HashSet<>(pspToHandle));
        var apiKey = pspConfiguration.get(pspId);
        assertTrue(apiKey.isRight());
        assertEquals("key-%s".formatted(pspId), apiKey.get());
    }

    @Test
    void shouldThrowErrorWhenRetrievingUnknownPspApiKey() {
        it.pagopa.ecommerce.commons.utils.NpgPspApiKeysConfig pspConfiguration = npgPspApiKeysConfig
                .npgCardsApiKeys(pspConfigurationJson, new HashSet<>(pspToHandle));
        var apiKey = pspConfiguration.get("unknown");
        assertTrue(apiKey.isLeft());
        assertEquals(
                "Requested API key for PSP: [unknown]. Available PSPs: [psp1, psp2, psp3]",
                apiKey.getLeft().getMessage()
        );
    }

    @Test
    void shouldThrowExceptionForInvalidJsonStructure() {
        Set<String> psps = new HashSet<>(pspToHandle);
        NpgApiKeyConfigurationException exception = assertThrows(
                NpgApiKeyConfigurationException.class,
                () -> npgPspApiKeysConfig.npgCardsApiKeys("{", psps)
        );
        assertEquals(
                "Error parsing NPG PSP api keys configuration for payment method: [CARDS], cause: Invalid json configuration map",
                exception.getMessage()
        );
    }

    @Test
    void shouldThrowExceptionForMissingPspId() {
        Set<String> psps = new HashSet<>(pspToHandle);
        psps.add("psp4");
        NpgApiKeyConfigurationException exception = assertThrows(
                NpgApiKeyConfigurationException.class,
                () -> npgPspApiKeysConfig.npgCardsApiKeys(pspConfigurationJson, psps)
        );
        assertEquals(
                "Error parsing NPG PSP api keys configuration for payment method: [CARDS], cause: Misconfigured api keys. Missing keys: [psp4]",
                exception.getMessage()
        );
    }

    private static Stream<Arguments> npgApiKeyHandlerTestMethodSource() {
        return Stream.of(
                Arguments.of(NpgClient.PaymentMethod.CARDS, "psp1"),
                Arguments.of(NpgClient.PaymentMethod.CARDS, "psp2"),
                Arguments.of(NpgClient.PaymentMethod.CARDS, "psp3"),
                Arguments.of(NpgClient.PaymentMethod.PAYPAL, "psp1"),
                Arguments.of(NpgClient.PaymentMethod.PAYPAL, "psp2"),
                Arguments.of(NpgClient.PaymentMethod.PAYPAL, "psp3"),
                Arguments.of(NpgClient.PaymentMethod.MYBANK, "psp1"),
                Arguments.of(NpgClient.PaymentMethod.PAYPAL, "psp2"),
                Arguments.of(NpgClient.PaymentMethod.PAYPAL, "psp3"),
                Arguments.of(NpgClient.PaymentMethod.BANCOMATPAY, "psp1"),
                Arguments.of(NpgClient.PaymentMethod.BANCOMATPAY, "psp2"),
                Arguments.of(NpgClient.PaymentMethod.BANCOMATPAY, "psp3")
        );
    }

    @ParameterizedTest
    @MethodSource("npgApiKeyHandlerTestMethodSource")
    void shouldBuildNpgApiKeyHandlerSuccessfully(
                                                 NpgClient.PaymentMethod paymentMethod,
                                                 String pspId
    ) {
        // pre-requisites
        String pspConfigurationJson = """
                {
                    "psp1" : "%1$s-key-psp1",
                    "psp2" : "%1$s-key-psp2",
                    "psp3" : "%1$s-key-psp3"
                }
                """;
        String expectedDefaultApiKey = "defaultApiKey";
        String expectedPspApiKey = "%s-key-%s".formatted(paymentMethod, pspId);
        NpgApiKeyHandler npgApiKeyHandler = npgPspApiKeysConfig.npgApiKeyHandler(
                npgPspApiKeysConfig.npgCardsApiKeys(
                        pspConfigurationJson.formatted(NpgClient.PaymentMethod.CARDS),
                        pspToHandle
                ),
                npgPspApiKeysConfig.npgCardsApiKeys(
                        pspConfigurationJson.formatted(NpgClient.PaymentMethod.BANCOMATPAY),
                        pspToHandle
                ),
                npgPspApiKeysConfig.npgCardsApiKeys(
                        pspConfigurationJson.formatted(NpgClient.PaymentMethod.MYBANK),
                        pspToHandle
                ),
                npgPspApiKeysConfig.npgCardsApiKeys(
                        pspConfigurationJson.formatted(NpgClient.PaymentMethod.PAYPAL),
                        pspToHandle
                ),
                expectedDefaultApiKey
        );
        Either<NpgApiKeyConfigurationException, String> apiKey = npgApiKeyHandler
                .getApiKeyForPaymentMethod(paymentMethod, pspId);
        String defaultNpgApiKey = npgApiKeyHandler.getDefaultApiKey();

        assertEquals(expectedDefaultApiKey, defaultNpgApiKey);

        assertEquals(expectedPspApiKey, apiKey.get());

    }

    @ParameterizedTest
    @MethodSource("npgApiKeyHandlerTestMethodSource")
    void shouldReturnErrorForMissingPspApiKey(NpgClient.PaymentMethod paymentMethod) {
        // pre-requisites
        String pspConfigurationJson = """
                {
                    "psp1" : "%1$s-key-psp1",
                    "psp2" : "%1$s-key-psp2",
                    "psp3" : "%1$s-key-psp3"
                }
                """;
        NpgApiKeyHandler npgApiKeyHandler = npgPspApiKeysConfig.npgApiKeyHandler(
                npgPspApiKeysConfig.npgCardsApiKeys(
                        pspConfigurationJson.formatted(NpgClient.PaymentMethod.CARDS),
                        pspToHandle
                ),
                npgPspApiKeysConfig.npgCardsApiKeys(
                        pspConfigurationJson.formatted(NpgClient.PaymentMethod.BANCOMATPAY),
                        pspToHandle
                ),
                npgPspApiKeysConfig.npgCardsApiKeys(
                        pspConfigurationJson.formatted(NpgClient.PaymentMethod.MYBANK),
                        pspToHandle
                ),
                npgPspApiKeysConfig.npgCardsApiKeys(
                        pspConfigurationJson.formatted(NpgClient.PaymentMethod.PAYPAL),
                        pspToHandle
                ),
                "defaultApiKey"
        );
        Either<NpgApiKeyConfigurationException, String> apiKey = npgApiKeyHandler
                .getApiKeyForPaymentMethod(paymentMethod, "missingPspId");
        assertEquals(
                "Cannot retrieve api key for payment method: [%s]. Cause: Requested API key for PSP: [missingPspId]. Available PSPs: [psp1, psp2, psp3]"
                        .formatted(paymentMethod),
                apiKey.getLeft().getMessage()
        );

    }
}
