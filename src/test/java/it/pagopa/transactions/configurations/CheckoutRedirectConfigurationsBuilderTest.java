package it.pagopa.transactions.configurations;

import it.pagopa.ecommerce.commons.exceptions.CheckoutRedirectConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CheckoutRedirectConfigurationsBuilderTest {

    private final CheckoutRedirectConfigurationsBuilder checkoutRedirectConfigurationsBuilder = new CheckoutRedirectConfigurationsBuilder();

    private Set<String> pspToHandle = Set.of("psp1", "psp2", "psp3");

    private final String pspConfigurationApiKeyJson = """
            {
                "psp1" : "key-psp1",
                "psp2" : "key-psp2",
                "psp3" : "key-psp3"
            }
            """;

    private final Map<String, String> pspUriMap = Map.of(
            "psp1",
            "http://localhost/psp1/redirectionUrl",
            "psp2",
            "http://localhost/psp2/redirectionUrl",
            "psp3",
            "http://localhost/psp3/redirectionUrl"
    );

    private final Map<String, String> pspLogoMap = Map.of(
            "psp1",
            "http://localhost/psp1/logo",
            "psp2",
            "http://localhost/psp2/logo",
            "psp3",
            "http://localhost/psp3/logo"
    );

    @Test
    void shouldBuildPspApiKeySuccessfully() {
        assertDoesNotThrow(
                () -> checkoutRedirectConfigurationsBuilder
                        .checkoutRedirectApiKeys(pspConfigurationApiKeyJson, pspToHandle)
        );
    }

    @Test
    void shouldThrowExceptionForErrorBuildingApiKeyMapping() {
        CheckoutRedirectConfigurationException exception = assertThrows(
                CheckoutRedirectConfigurationException.class,
                () -> checkoutRedirectConfigurationsBuilder.checkoutRedirectApiKeys("{", pspToHandle)
        );
        assertEquals(
                "Error parsing Checkout Redirect PSP API_KEYS configuration, cause: Invalid json configuration map",
                exception.getMessage()
        );
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                    "psp1",
                    "psp2",
                    "psp3"
            }
    )
    void shouldBuildPspBackendUriMapSuccessfully(String pspId) {
        Map<String, URI> mapping = assertDoesNotThrow(
                () -> checkoutRedirectConfigurationsBuilder.checkoutRedirectBeApiCallUriMap(pspToHandle, pspUriMap)
        );
        assertEquals("http://localhost/%s/redirectionUrl".formatted(pspId), mapping.get(pspId).toString());

    }

    @Test
    void shouldThrowExceptionBuildingBackendUriMapForMissingApiKey() {
        Map<String, String> missingKeyPspMap = new HashMap<>(pspUriMap);
        missingKeyPspMap.remove("psp1");
        CheckoutRedirectConfigurationException e = assertThrows(
                CheckoutRedirectConfigurationException.class,
                () -> checkoutRedirectConfigurationsBuilder
                        .checkoutRedirectBeApiCallUriMap(pspToHandle, missingKeyPspMap)
        );
        assertEquals(
                "Error parsing Checkout Redirect PSP BACKEND_URLS configuration, cause: Misconfigured checkout.redirect.pspUrlMapping, the following PSP b.e. URIs are not configured: [psp1]",
                e.getMessage()
        );

    }

    @Test
    void shouldThrowExceptionBuildingBackendUriMapForWrongUri() {
        Map<String, String> missingKeyPspMap = new HashMap<>(pspUriMap);
        missingKeyPspMap.put("psp1", "http:\\\\localhost");
        assertThrows(
                IllegalArgumentException.class,
                () -> checkoutRedirectConfigurationsBuilder
                        .checkoutRedirectBeApiCallUriMap(pspToHandle, missingKeyPspMap)
        );
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                    "psp1",
                    "psp2",
                    "psp3"
            }
    )
    void shouldBuildPspLogoUriMapSuccessfully(String pspId) {
        Map<String, URI> mapping = assertDoesNotThrow(
                () -> checkoutRedirectConfigurationsBuilder.checkoutRedirectLogoMap(pspToHandle, pspLogoMap)
        );
        assertEquals("http://localhost/%s/logo".formatted(pspId), mapping.get(pspId).toString());

    }

    @Test
    void shouldThrowExceptionBuildingLogoUriMapForMissingApiKey() {
        Map<String, String> missingKeyPspMap = new HashMap<>(pspLogoMap);
        missingKeyPspMap.remove("psp1");
        CheckoutRedirectConfigurationException e = assertThrows(
                CheckoutRedirectConfigurationException.class,
                () -> checkoutRedirectConfigurationsBuilder.checkoutRedirectLogoMap(pspToHandle, missingKeyPspMap)
        );
        assertEquals(
                "Error parsing Checkout Redirect PSP LOGOS configuration, cause: Misconfigured checkout.redirect.pspLogoMapping, the following PSP logos are not configured: [psp1]",
                e.getMessage()
        );

    }

    @Test
    void shouldThrowExceptionBuildingLogoUriMapForWrongUri() {
        Map<String, String> missingKeyPspMap = new HashMap<>(pspLogoMap);
        missingKeyPspMap.put("psp1", "http:\\\\localhost");
        assertThrows(
                IllegalArgumentException.class,
                () -> checkoutRedirectConfigurationsBuilder.checkoutRedirectLogoMap(pspToHandle, missingKeyPspMap)
        );
    }

}
