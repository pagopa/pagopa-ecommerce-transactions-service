package it.pagopa.transactions.client;

import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.exceptions.CheckoutRedirectMissingPspRequestedException;
import it.pagopa.ecommerce.commons.utils.CheckoutRedirectPspApiKeysConfig;
import it.pagopa.generated.ecommerce.redirect.v1.api.B2bPspSideApi;
import it.pagopa.generated.ecommerce.redirect.v1.auth.ApiKeyAuth;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class CheckoutRedirectClientBuilderTest {

    private final Map<String, URI> checkoutRedirectUriMapping = Map.of(
            "psp1",
            URI.create("http://localhost/psp1"),
            "psp2",
            URI.create("http://localhost/psp2"),
            "psp3",
            URI.create("http://localhost/psp3")
    );

    private final CheckoutRedirectPspApiKeysConfig checkoutRedirectPspApiKeysConfig = Mockito
            .mock(CheckoutRedirectPspApiKeysConfig.class);

    private final int readTimeout = 10000;

    private final int connectionTimeout = 10000;

    private final CheckoutRedirectClientBuilder checkoutRedirectClientBuilder = Mockito.spy(
            new CheckoutRedirectClientBuilder(
                    checkoutRedirectUriMapping,
                    checkoutRedirectPspApiKeysConfig,
                    readTimeout,
                    connectionTimeout
            )
    );

    @ParameterizedTest
    @ValueSource(
            strings = {
                    "psp1",
                    "psp2"
            }
    )
    void shouldLazilyBuildNewClientForPspSuccessfully(String pspId) {
        // pre-requisite
        String expectedPspApiKey = "%sApiKey".formatted(pspId);
        String expectedBaseUrl = "http://localhost/%s".formatted(pspId);
        given(checkoutRedirectPspApiKeysConfig.get(pspId)).willReturn(Either.right(expectedPspApiKey));
        // test
        Either<CheckoutRedirectMissingPspRequestedException, B2bPspSideApi> client = checkoutRedirectClientBuilder
                .getApiClientForPsp(pspId);
        // assertions
        assertTrue(client.isRight());
        B2bPspSideApi apiClient = client.get();
        ApiKeyAuth authentication = (ApiKeyAuth) apiClient.getApiClient().getAuthentication("PspApiKeyAuth");
        assertEquals(expectedPspApiKey, authentication.getApiKey());
        assertEquals("header", authentication.getLocation());
        assertEquals("x-api-key", authentication.getParamName());
        assertEquals(expectedPspApiKey, authentication.getApiKey());
        assertEquals(expectedBaseUrl, apiClient.getApiClient().getBasePath());
    }

    @Test
    void shouldReturnErrorConstructingClientForMissingPspBackendUriConfig() {
        String pspId = "idPspUnknown";
        given(checkoutRedirectPspApiKeysConfig.get(pspId)).willReturn(Either.right("pspKey"));
        Either<CheckoutRedirectMissingPspRequestedException, B2bPspSideApi> client = checkoutRedirectClientBuilder
                .getApiClientForPsp(pspId);
        assertTrue(client.isLeft());
        assertTrue(
                client.getLeft().getMessage()
                        .startsWith("Requested configuration value in BACKEND_URLS not available for PSP idPspUnknown.")
        );
    }

    @Test
    void shouldReturnCachedApiClientForApiClientCacheHit() {
        // pre-requisite
        String pspId = "psp3";
        String expectedPspApiKey = "%sApiKey".formatted(pspId);
        given(checkoutRedirectPspApiKeysConfig.get(pspId)).willReturn(Either.right(expectedPspApiKey));
        // test
        assertFalse(checkoutRedirectClientBuilder.checkoutRedirectClientMap.containsKey(pspId));
        Either<CheckoutRedirectMissingPspRequestedException, B2bPspSideApi> firstBuildClient = checkoutRedirectClientBuilder
                .getApiClientForPsp(pspId);
        Either<CheckoutRedirectMissingPspRequestedException, B2bPspSideApi> secondBuildClient = checkoutRedirectClientBuilder
                .getApiClientForPsp(pspId);
        // assertions
        assertTrue(firstBuildClient.isRight());
        assertTrue(secondBuildClient.isRight());
        assertTrue(checkoutRedirectClientBuilder.checkoutRedirectClientMap.containsKey(pspId));
        verify(checkoutRedirectClientBuilder, times(1)).buildApiClientForPsp(expectedPspApiKey, pspId);
    }

}
