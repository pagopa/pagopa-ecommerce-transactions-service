package it.pagopa.transactions.configurations;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import it.pagopa.ecommerce.commons.client.NodeForwarderClient;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlRequestDto;
import it.pagopa.generated.ecommerce.redirect.v1.dto.RedirectUrlResponseDto;
import it.pagopa.transactions.utils.soap.Jaxb2SoapDecoder;
import it.pagopa.transactions.utils.soap.Jaxb2SoapEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.codec.StringDecoder;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.json.Jackson2JsonDecoder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientsConfig {

    @Bean(name = "nodoWebClient")
    public WebClient nodoWebClient(
                                   @Value("${nodo.hostname}") String nodoHostname,
                                   @Value("${nodo.readTimeout}") int nodoReadTimeout,
                                   @Value("${nodo.connectionTimeout}") int nodoConnectionTimeout
    ) {

        HttpClient httpClient = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, nodoConnectionTimeout)
                .doOnConnected(
                        connection -> connection
                                .addHandlerLast(new ReadTimeoutHandler(nodoReadTimeout, TimeUnit.MILLISECONDS))
                )
                .resolver(nameResolverSpec -> nameResolverSpec.ndots(1));

        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder().codecs(clientCodecConfigurer -> {

            ObjectMapper mapper = getNodeObjectMapper();
            clientCodecConfigurer.registerDefaults(false);
            clientCodecConfigurer.customCodecs().register(StringDecoder.allMimeTypes());
            clientCodecConfigurer.customCodecs().register(new Jaxb2SoapDecoder());
            clientCodecConfigurer.customCodecs().register(new Jaxb2SoapEncoder());
            clientCodecConfigurer.customCodecs().register(new Jackson2JsonDecoder(mapper, MediaType.APPLICATION_JSON));
            clientCodecConfigurer.customCodecs().register(new Jackson2JsonEncoder(mapper, MediaType.APPLICATION_JSON));
        }).build();

        return WebClient.builder().baseUrl(nodoHostname)
                .clientConnector(new ReactorClientHttpConnector(httpClient)).exchangeStrategies(exchangeStrategies)
                .build();
    }

    public ObjectMapper getNodeObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        return mapper;
    }

    @Bean(name = "ecommercePaymentMethodWebClientV1")
    public it.pagopa.generated.ecommerce.paymentmethods.v1.api.PaymentMethodsApi ecommercePaymentMethodWebClientV1(
                                                                                                                   @Value(
                                                                                                                       "${ecommercePaymentMethods.uri}"
                                                                                                                   ) String ecommercePaymentMethodsUri,
                                                                                                                   @Value(
                                                                                                                       "${ecommercePaymentMethods.readTimeout}"
                                                                                                                   ) int ecommercePaymentMethodsReadTimeout,
                                                                                                                   @Value(
                                                                                                                       "${ecommercePaymentMethods.connectionTimeout}"
                                                                                                                   ) int ecommercePaymentMethodsConnectionTimeout,
                                                                                                                   @Value(
                                                                                                                       "${ecommercePaymentMethods.apiKey}"
                                                                                                                   ) String apiKey
    ) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, ecommercePaymentMethodsConnectionTimeout)
                .doOnConnected(
                        connection -> connection.addHandlerLast(
                                new ReadTimeoutHandler(
                                        ecommercePaymentMethodsReadTimeout,
                                        TimeUnit.MILLISECONDS
                                )
                        )
                ).resolver(nameResolverSpec -> nameResolverSpec.ndots(1));

        WebClient webClient = it.pagopa.generated.ecommerce.paymentmethods.v1.ApiClient.buildWebClientBuilder()
                .clientConnector(
                        new ReactorClientHttpConnector(httpClient)
                ).baseUrl(ecommercePaymentMethodsUri).build();

        it.pagopa.generated.ecommerce.paymentmethods.v1.ApiClient apiClient = new it.pagopa.generated.ecommerce.paymentmethods.v1.ApiClient(
                webClient
        ).setBasePath(ecommercePaymentMethodsUri);
        apiClient.setApiKey(apiKey);
        return new it.pagopa.generated.ecommerce.paymentmethods.v1.api.PaymentMethodsApi(apiClient);
    }

    @Bean(name = "walletWebClient")
    public it.pagopa.generated.wallet.v1.api.WalletsApi walletWebClient(
                                                                        @Value(
                                                                            "${wallet.uri}"
                                                                        ) String walletUri,
                                                                        @Value(
                                                                            "${wallet.readTimeout}"
                                                                        ) int walletReadTimeout,
                                                                        @Value(
                                                                            "${wallet.connectionTimeout}"
                                                                        ) int walletConnectionTimeout,
                                                                        @Value(
                                                                            "${wallet.apiKey}"
                                                                        ) String apiKey
    ) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, walletConnectionTimeout)
                .doOnConnected(
                        connection -> connection.addHandlerLast(
                                new ReadTimeoutHandler(
                                        walletReadTimeout,
                                        TimeUnit.MILLISECONDS
                                )
                        )
                ).resolver(nameResolverSpec -> nameResolverSpec.ndots(1));

        WebClient webClient = it.pagopa.generated.wallet.v1.ApiClient.buildWebClientBuilder()
                .clientConnector(
                        new ReactorClientHttpConnector(httpClient)
                ).baseUrl(walletUri).build();

        it.pagopa.generated.wallet.v1.ApiClient apiClient = new it.pagopa.generated.wallet.v1.ApiClient(
                webClient
        ).setBasePath(walletUri);
        apiClient.setApiKey(apiKey);
        return new it.pagopa.generated.wallet.v1.api.WalletsApi(apiClient);
    }

    @Bean
    public it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp() {
        return new it.pagopa.generated.transactions.model.ObjectFactory();
    }

    /**
     * Build node forwarder proxy api client
     *
     * @param apiKey            backend api key
     * @param backendUrl        backend URL
     * @param readTimeout       read timeout
     * @param connectionTimeout connection timeout
     * @return the build Node forwarder proxy api client
     */
    @Bean
    public NodeForwarderClient<RedirectUrlRequestDto, RedirectUrlResponseDto> nodeForwarderRedirectApiClient(
                                                                                                             @Value(
                                                                                                                 "${node.forwarder.apiKey}"
                                                                                                             ) String apiKey,
                                                                                                             @Value(
                                                                                                                 "${node.forwarder.url}"
                                                                                                             ) String backendUrl,
                                                                                                             @Value(
                                                                                                                 "${node.forwarder.readTimeout}"
                                                                                                             ) int readTimeout,
                                                                                                             @Value(
                                                                                                                 "${node.forwarder.connectionTimeout}"
                                                                                                             ) int connectionTimeout
    ) {

        return new NodeForwarderClient<>(
                apiKey,
                backendUrl,
                readTimeout,
                connectionTimeout
        );
    }

    @Bean(name = "ecommercePaymentMethodWebClientV2")
    public it.pagopa.generated.ecommerce.paymentmethods.v2.api.PaymentMethodsApi ecommercePaymentMethodWebClientV2(
                                                                                                                   @Value(
                                                                                                                       "${ecommercePaymentMethods.v2.uri}"
                                                                                                                   ) String ecommercePaymentMethodsUri,
                                                                                                                   @Value(
                                                                                                                       "${ecommercePaymentMethods.readTimeout}"
                                                                                                                   ) int ecommercePaymentMethodsReadTimeout,
                                                                                                                   @Value(
                                                                                                                       "${ecommercePaymentMethods.connectionTimeout}"
                                                                                                                   ) int ecommercePaymentMethodsConnectionTimeout,
                                                                                                                   @Value(
                                                                                                                       "${ecommercePaymentMethods.apiKey}"
                                                                                                                   ) String apiKey
    ) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, ecommercePaymentMethodsConnectionTimeout)
                .doOnConnected(
                        connection -> connection.addHandlerLast(
                                new ReadTimeoutHandler(
                                        ecommercePaymentMethodsReadTimeout,
                                        TimeUnit.MILLISECONDS
                                )
                        )
                ).resolver(nameResolverSpec -> nameResolverSpec.ndots(1));

        WebClient webClient = it.pagopa.generated.ecommerce.paymentmethods.v2.ApiClient.buildWebClientBuilder()
                .clientConnector(
                        new ReactorClientHttpConnector(httpClient)
                ).baseUrl(ecommercePaymentMethodsUri).build();

        it.pagopa.generated.ecommerce.paymentmethods.v2.ApiClient apiClient = new it.pagopa.generated.ecommerce.paymentmethods.v2.ApiClient(
                webClient
        ).setBasePath(ecommercePaymentMethodsUri);
        apiClient.setApiKey(apiKey);
        return new it.pagopa.generated.ecommerce.paymentmethods.v2.api.PaymentMethodsApi(apiClient);
    }

}
