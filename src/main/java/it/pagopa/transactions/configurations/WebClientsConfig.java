package it.pagopa.transactions.configurations;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import it.pagopa.generated.ecommerce.gateway.v1.api.PostePayInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.api.VposInternalApi;
import it.pagopa.generated.ecommerce.gateway.v1.api.XPayInternalApi;
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
                );

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

    @Bean(name = "paymentTransactionGatewayPostepayWebClient")
    public PostePayInternalApi paymentTransactionGatewayPostepayWebClient(
                                                                          @Value(
                                                                              "${paymentTransactionsGateway.uri}"
                                                                          ) String paymentTransactionGatewayUri,
                                                                          @Value(
                                                                              "${paymentTransactionsGateway.readTimeout}"
                                                                          ) int paymentTransactionGatewayReadTimeout,
                                                                          @Value(
                                                                              "${paymentTransactionsGateway.connectionTimeout}"
                                                                          ) int paymentTransactionGatewayConnectionTimeout,
                                                                          @Value(
                                                                              "${paymentTransactionsGateway.apiKey}"
                                                                          ) String apiKey
    ) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, paymentTransactionGatewayConnectionTimeout)
                .doOnConnected(
                        connection -> connection.addHandlerLast(
                                new ReadTimeoutHandler(
                                        paymentTransactionGatewayReadTimeout,
                                        TimeUnit.MILLISECONDS
                                )
                        )
                );

        WebClient webClient = it.pagopa.generated.ecommerce.gateway.v1.ApiClient.buildWebClientBuilder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(paymentTransactionGatewayUri)
                .build();
        it.pagopa.generated.ecommerce.gateway.v1.ApiClient apiClient = new it.pagopa.generated.ecommerce.gateway.v1.ApiClient(
                webClient
        );
        apiClient.setBasePath(paymentTransactionGatewayUri);
        apiClient.setApiKey(apiKey);
        return new PostePayInternalApi(apiClient);
    }

    @Bean(name = "paymentTransactionGatewayXPayWebClient")
    public XPayInternalApi paymentTransactionGatewayXPayWebClient(
                                                                  @Value(
                                                                      "${paymentTransactionsGateway.uri}"
                                                                  ) String paymentTransactionGatewayUri,
                                                                  @Value(
                                                                      "${paymentTransactionsGateway.readTimeout}"
                                                                  ) int paymentTransactionGatewayReadTimeout,
                                                                  @Value(
                                                                      "${paymentTransactionsGateway.connectionTimeout}"
                                                                  ) int paymentTransactionGatewayConnectionTimeout,
                                                                  @Value(
                                                                      "${paymentTransactionsGateway.apiKey}"
                                                                  ) String apiKey
    ) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, paymentTransactionGatewayConnectionTimeout)
                .doOnConnected(
                        connection -> connection.addHandlerLast(
                                new ReadTimeoutHandler(
                                        paymentTransactionGatewayReadTimeout,
                                        TimeUnit.MILLISECONDS
                                )
                        )
                );

        WebClient webClient = it.pagopa.generated.ecommerce.gateway.v1.ApiClient.buildWebClientBuilder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(paymentTransactionGatewayUri)
                .build();
        it.pagopa.generated.ecommerce.gateway.v1.ApiClient apiClient = new it.pagopa.generated.ecommerce.gateway.v1.ApiClient(
                webClient
        );
        apiClient.setBasePath(paymentTransactionGatewayUri);
        apiClient.setApiKey(apiKey);
        return new XPayInternalApi(apiClient);
    }

    @Bean(name = "creditCardInternalApiClient")
    public VposInternalApi creditCardInternalApiClient(
                                                       @Value(
                                                           "${paymentTransactionsGateway.uri}"
                                                       ) String paymentTransactionGatewayUri,
                                                       @Value(
                                                           "${paymentTransactionsGateway.readTimeout}"
                                                       ) int paymentTransactionGatewayReadTimeout,
                                                       @Value(
                                                           "${paymentTransactionsGateway.connectionTimeout}"
                                                       ) int paymentTransactionGatewayConnectionTimeout,
                                                       @Value(
                                                           "${paymentTransactionsGateway.apiKey}"
                                                       ) String apiKey
    ) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, paymentTransactionGatewayConnectionTimeout)
                .doOnConnected(
                        connection -> connection.addHandlerLast(
                                new ReadTimeoutHandler(
                                        paymentTransactionGatewayReadTimeout,
                                        TimeUnit.MILLISECONDS
                                )
                        )
                );

        WebClient webClient = it.pagopa.generated.ecommerce.gateway.v1.ApiClient.buildWebClientBuilder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(paymentTransactionGatewayUri)
                .build();
        it.pagopa.generated.ecommerce.gateway.v1.ApiClient apiClient = new it.pagopa.generated.ecommerce.gateway.v1.ApiClient(
                webClient
        );
        apiClient.setBasePath(paymentTransactionGatewayUri);
        apiClient.setApiKey(apiKey);
        return new VposInternalApi(apiClient);
    }

    @Bean(name = "ecommercePaymentInstrumentsWebClient")
    public it.pagopa.generated.ecommerce.paymentmethods.v1.api.PaymentMethodsApi ecommercePaymentInstrumentsWebClient(
                                                                                                                      @Value(
                                                                                                                          "${ecommercePaymentMethods.uri}"
                                                                                                                      ) String ecommercePaymentInstrumentsUri,
                                                                                                                      @Value(
                                                                                                                          "${ecommercePaymentMethods.readTimeout}"
                                                                                                                      ) int ecommercePaymentInstrumentsReadTimeout,
                                                                                                                      @Value(
                                                                                                                          "${ecommercePaymentMethods.connectionTimeout}"
                                                                                                                      ) int ecommercePaymentInstrumentsConnectionTimeout,
                                                                                                                      @Value(
                                                                                                                          "${ecommercePaymentMethods.apiKey}"
                                                                                                                      ) String apiKey
    ) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, ecommercePaymentInstrumentsConnectionTimeout)
                .doOnConnected(
                        connection -> connection.addHandlerLast(
                                new ReadTimeoutHandler(
                                        ecommercePaymentInstrumentsReadTimeout,
                                        TimeUnit.MILLISECONDS
                                )
                        )
                );

        WebClient webClient = it.pagopa.generated.ecommerce.paymentmethods.v1.ApiClient.buildWebClientBuilder()
                .clientConnector(
                        new ReactorClientHttpConnector(httpClient)
                ).baseUrl(ecommercePaymentInstrumentsUri).build();

        it.pagopa.generated.ecommerce.paymentmethods.v1.ApiClient apiClient = new it.pagopa.generated.ecommerce.paymentmethods.v1.ApiClient(
                webClient
        ).setBasePath(ecommercePaymentInstrumentsUri);
        apiClient.setApiKey(apiKey);
        return new it.pagopa.generated.ecommerce.paymentmethods.v1.api.PaymentMethodsApi(apiClient);
    }

    @Bean
    public it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp() {
        return new it.pagopa.generated.transactions.model.ObjectFactory();
    }

}
