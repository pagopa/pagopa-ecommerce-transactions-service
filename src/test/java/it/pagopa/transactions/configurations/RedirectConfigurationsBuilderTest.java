package it.pagopa.transactions.configurations;

import it.pagopa.ecommerce.commons.exceptions.RedirectConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RedirectConfigurationsBuilderTest {

    private final RedirectConfigurationsBuilder checkoutRedirectConfigurationsBuilder = new RedirectConfigurationsBuilder();

    private Set<String> pspToHandle = Set.of("psp1", "psp2", "psp3");

    private final Map<String, String> pspUriMap = Map.of(
            "psp1",
            "http://localhost/psp1/redirectionUrl",
            "psp2",
            "http://localhost/psp2/redirectionUrl",
            "psp3",
            "http://localhost/psp3/redirectionUrl"
    );

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
                () -> checkoutRedirectConfigurationsBuilder.redirectBeApiCallUriMap(pspToHandle, pspUriMap)
        );
        assertEquals("http://localhost/%s/redirectionUrl".formatted(pspId), mapping.get(pspId).toString());

    }

    @Test
    void shouldThrowExceptionBuildingBackendUriMapForMissingApiKey() {
        Map<String, String> missingKeyPspMap = new HashMap<>(pspUriMap);
        missingKeyPspMap.remove("psp1");
        RedirectConfigurationException e = assertThrows(
                RedirectConfigurationException.class,
                () -> checkoutRedirectConfigurationsBuilder
                        .redirectBeApiCallUriMap(pspToHandle, missingKeyPspMap)
        );
        assertEquals(
                "Error parsing Redirect PSP BACKEND_URLS configuration, cause: Misconfigured redirect.pspUrlMapping, the following PSP b.e. URIs are not configured: [psp1]",
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
                        .redirectBeApiCallUriMap(pspToHandle, missingKeyPspMap)
        );
    }

}
