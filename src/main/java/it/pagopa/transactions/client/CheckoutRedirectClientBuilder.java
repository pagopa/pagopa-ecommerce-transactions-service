package it.pagopa.transactions.client;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.exceptions.CheckoutRedirectConfigurationType;
import it.pagopa.ecommerce.commons.exceptions.CheckoutRedirectMissingPspRequestedException;
import it.pagopa.ecommerce.commons.utils.CheckoutRedirectPspApiKeysConfig;
import it.pagopa.generated.ecommerce.redirect.v1.ApiClient;
import it.pagopa.generated.ecommerce.redirect.v1.api.B2bPspSideApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Checkout redirect builder class. This class handle all checkout redirect
 * {@link B2bPspSideApi} instance, one per checkout redirect URL Clients are
 * build lazily, once requested, configuring reading timeout, api key, base url
 * and so on
 */
@Component
public class CheckoutRedirectClientBuilder {

    /**
     * Internal caching map that will contain psp to api client mapping
     */
    final Map<String, B2bPspSideApi> checkoutRedirectClientMap = new HashMap<>();

    /**
     * PSP id to Checkout Redirect backend mapping
     */
    private final Map<String, URI> checkoutRedirectBeApiCallUriMap;

    /**
     * Checkout redirect psp api key configuration
     */
    private final CheckoutRedirectPspApiKeysConfig checkoutRedirectApiKeys;

    /**
     * Checkout redirect api call read timeout
     */
    private final int checkoutRedirectReadTimeout;
    /**
     * Checkout redirect api call connection timeout
     */
    private final int checkoutRedirectConnectionTimeout;

    /**
     * Constructor
     *
     * @param checkoutRedirectBeApiCallUriMap   psp to URI configuration map
     * @param checkoutRedirectApiKeys           psp to api key configuration map
     * @param checkoutRedirectReadTimeout       redirection read timeout
     * @param checkoutRedirectConnectionTimeout redirection connection timeout
     */
    @Autowired
    public CheckoutRedirectClientBuilder(
            Map<String, URI> checkoutRedirectBeApiCallUriMap,
            CheckoutRedirectPspApiKeysConfig checkoutRedirectApiKeys,
            @Value("${checkout.redirect.readTimeout}") int checkoutRedirectReadTimeout,
            @Value("${checkout.redirect.connectionTimeout}") int checkoutRedirectConnectionTimeout
    ) {
        this.checkoutRedirectBeApiCallUriMap = checkoutRedirectBeApiCallUriMap;
        this.checkoutRedirectApiKeys = checkoutRedirectApiKeys;
        this.checkoutRedirectReadTimeout = checkoutRedirectReadTimeout;
        this.checkoutRedirectConnectionTimeout = checkoutRedirectConnectionTimeout;
    }

    /**
     * Return {@link B2bPspSideApi} api client lazily instantiating it if called for
     * the first time
     *
     * @param pspId - the psp id for which retrieve the associated api client
     * @return either the api client associated to the PSP id or an error if an api
     *         client for input PSP id cannot be build (missing required
     *         configurations)
     */
    public Either<CheckoutRedirectMissingPspRequestedException, B2bPspSideApi> getApiClientForPsp(
                                                                                                  String pspId
    ) {
        Either<CheckoutRedirectMissingPspRequestedException, B2bPspSideApi> apiClientEither;
        B2bPspSideApi client = checkoutRedirectClientMap.get(pspId);
        if (client == null) {
            // lazy api client instantiation
            apiClientEither = checkoutRedirectApiKeys
                    .get(pspId)
                    .flatMap(apiKey -> buildApiClientForPsp(apiKey, pspId));

        } else {
            // api client already present into cache, returning to the caller
            apiClientEither = Either.right(client);
        }
        return apiClientEither;
    }

    /**
     * Build a fresh new instance of {@link B2bPspSideApi} api client for the input
     * psp id
     *
     * @param apiKey the api key to be used for any api call
     * @param pspId  the psp id associated to the created api client
     * @return either the created api client or an exception in case of missing URL
     *         configuration for input psp id
     */

    Either<CheckoutRedirectMissingPspRequestedException, B2bPspSideApi> buildApiClientForPsp(
                                                                                             String apiKey,
                                                                                             String pspId
    ) {
        URI pspUri = checkoutRedirectBeApiCallUriMap.get(pspId);
        Either<CheckoutRedirectMissingPspRequestedException, B2bPspSideApi> taskEither;
        if (pspUri != null) {
            HttpClient httpClient = HttpClient.create()
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, checkoutRedirectConnectionTimeout)
                    .doOnConnected(
                            connection -> connection.addHandlerLast(
                                    new ReadTimeoutHandler(
                                            checkoutRedirectReadTimeout,
                                            TimeUnit.MILLISECONDS
                                    )
                            )
                    );

            WebClient webClient = ApiClient.buildWebClientBuilder()
                    .clientConnector(
                            new ReactorClientHttpConnector(httpClient)
                    ).baseUrl(pspUri.toString()).build();

            ApiClient apiClient = new ApiClient(
                    webClient
            ).setBasePath(pspUri.toString());
            apiClient.setApiKey(apiKey);
            B2bPspSideApi b2bPspSideApi = new B2bPspSideApi(apiClient);
            checkoutRedirectClientMap.put(pspId, b2bPspSideApi);
            taskEither = Either.right(b2bPspSideApi);
        } else {
            taskEither = Either.left(
                    new CheckoutRedirectMissingPspRequestedException(
                            pspId,
                            checkoutRedirectBeApiCallUriMap.keySet(),
                            CheckoutRedirectConfigurationType.BACKEND_URLS
                    )
            );
        }
        return taskEither;
    }

}
