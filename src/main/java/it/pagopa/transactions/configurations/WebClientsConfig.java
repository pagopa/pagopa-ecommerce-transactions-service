package it.pagopa.transactions.configurations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import it.pagopa.generated.ecommerce.gateway.v1.api.PaymentTransactionsControllerApi;
import it.pagopa.generated.ecommerce.nodo.v1.api.NodoApi;
import it.pagopa.generated.ecommerce.sessions.v1.ApiClient;
import it.pagopa.generated.ecommerce.sessions.v1.api.DefaultApi;
import it.pagopa.transactions.utils.soap.Jaxb2SoapDecoder;
import it.pagopa.transactions.utils.soap.Jaxb2SoapEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

    @Bean(name = "nodoApiClient")
    public NodoApi nodoApiClient(@Value("${nodo.uri}") String nodoUri,
                                 @Value("${nodo.readTimeout}") int nodoReadTimeout,
                                 @Value("${nodo.connectionTimeout}") int nodoConnectionTimeout) {

        HttpClient httpClient = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, nodoConnectionTimeout)
                .doOnConnected(connection -> connection
                        .addHandlerLast(new ReadTimeoutHandler(nodoReadTimeout, TimeUnit.MILLISECONDS)));

        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder().codecs(clientCodecConfigurer -> {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            clientCodecConfigurer.registerDefaults(false);
            clientCodecConfigurer.customCodecs().register(new Jackson2JsonDecoder(mapper, MediaType.APPLICATION_JSON));
            clientCodecConfigurer.customCodecs().register(new Jackson2JsonEncoder(mapper, MediaType.APPLICATION_JSON));
        }).build();

        WebClient webClient = WebClient.builder().baseUrl(nodoUri)
                .clientConnector(new ReactorClientHttpConnector(httpClient)).exchangeStrategies(exchangeStrategies)
                .build();
        it.pagopa.generated.ecommerce.nodo.v1.ApiClient apiClient = new it.pagopa.generated.ecommerce.nodo.v1.ApiClient(webClient);

        NodoApi nodoApi = new NodoApi(apiClient);
        return nodoApi;
    }

    @Bean(name = "nodoWebClient")
    public WebClient nodoWebClient(@Value("${nodo.uri}") String nodoUri,
                                   @Value("${nodo.readTimeout}") int nodoReadTimeout,
                                   @Value("${nodo.connectionTimeout}") int nodoConnectionTimeout) {

        HttpClient httpClient = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, nodoConnectionTimeout)
                .doOnConnected(connection -> connection
                        .addHandlerLast(new ReadTimeoutHandler(nodoReadTimeout, TimeUnit.MILLISECONDS)));

        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder().codecs(clientCodecConfigurer -> {
            ObjectMapper mapper = new ObjectMapper();
            mapper.registerModule(new JavaTimeModule());
            mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            clientCodecConfigurer.registerDefaults(false);
            clientCodecConfigurer.customCodecs().register(new Jaxb2SoapDecoder());
            clientCodecConfigurer.customCodecs().register(new Jaxb2SoapEncoder());
            clientCodecConfigurer.customCodecs().register(new Jackson2JsonDecoder(mapper, MediaType.APPLICATION_JSON));
            clientCodecConfigurer.customCodecs().register(new Jackson2JsonEncoder(mapper, MediaType.APPLICATION_JSON));
        }).build();

        return WebClient.builder().baseUrl(nodoUri)
                .clientConnector(new ReactorClientHttpConnector(httpClient)).exchangeStrategies(exchangeStrategies)
                .build();
    }

    @Bean(name = "ecommerceSessionsWebClient")
    public DefaultApi
    ecommerceSessionsWebClient(@Value("${ecommerceSessions.uri}") String ecommerceSessionsUri,
                               @Value("${ecommerceSessions.readTimeout}") int ecommerceSessionsReadTimeout,
                               @Value("${ecommerceSessions.connectionTimeout}") int ecommerceSessionsConnectionTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, ecommerceSessionsConnectionTimeout)
                .doOnConnected(connection ->
                        connection.addHandlerLast(new ReadTimeoutHandler(
                                ecommerceSessionsReadTimeout,
                                TimeUnit.MILLISECONDS)));

        WebClient webClient = ApiClient.buildWebClientBuilder().clientConnector(
                new ReactorClientHttpConnector(httpClient)).baseUrl(ecommerceSessionsUri).build();

        return new DefaultApi(new ApiClient(webClient));
    }

    @Bean(name = "paymentTransactionGatewayWebClient")
    public PaymentTransactionsControllerApi
    paymentTransactionGateayWebClient(@Value("${paymentTransactionsGateway.uri}") String paymentTransactionGatewayUri,
                               @Value("${paymentTransactionsGateway.readTimeout}") int paymentTransactionGatewayReadTimeout,
                               @Value("${paymentTransactionsGateway.connectionTimeout}") int paymentTransactionGatewayConnectionTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, paymentTransactionGatewayConnectionTimeout)
                .doOnConnected(connection ->
                        connection.addHandlerLast(new ReadTimeoutHandler(
                                paymentTransactionGatewayReadTimeout,
                                TimeUnit.MILLISECONDS)));

        WebClient webClient = it.pagopa.generated.ecommerce.gateway.v1.ApiClient.buildWebClientBuilder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .baseUrl(paymentTransactionGatewayUri)
                .build();

        return new PaymentTransactionsControllerApi(new it.pagopa.generated.ecommerce.gateway.v1.ApiClient(webClient).setBasePath(paymentTransactionGatewayUri));
    }

    @Bean(name = "ecommercePaymentInstrumentsWebClient")
    public DefaultApi
    ecommercePaymentInstrumentsWebClient(@Value("${ecommercePaymentInstruments.uri}") String ecommercePaymentInstrumentsUri,
                               @Value("${ecommercePaymentInstruments.readTimeout}") int ecommercePaymentInstrumentsReadTimeout,
                               @Value("${ecommercePaymentInstruments.connectionTimeout}") int ecommercePaymentInstrumentsConnectionTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, ecommercePaymentInstrumentsConnectionTimeout)
                .doOnConnected(connection ->
                        connection.addHandlerLast(new ReadTimeoutHandler(
                                ecommercePaymentInstrumentsReadTimeout,
                                TimeUnit.MILLISECONDS)));

        WebClient webClient = ApiClient.buildWebClientBuilder().clientConnector(
                new ReactorClientHttpConnector(httpClient)).baseUrl(ecommercePaymentInstrumentsUri).build();

        return new DefaultApi(new ApiClient(webClient));
    }

    @Bean(name = "notificationsServiceWebClient")
    public it.pagopa.generated.notifications.v1.api.DefaultApi
    notificationsServiceWebClient(@Value("${notificationsService.uri}") String notificationsServiceUri,
                                         @Value("${notificationsService.readTimeout}") int notificationsServiceReadTimeout,
                                         @Value("${notificationsService.connectionTimeout}") int notificationsServiceConnectionTimeout) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, notificationsServiceConnectionTimeout)
                .doOnConnected(connection ->
                        connection.addHandlerLast(new ReadTimeoutHandler(
                                notificationsServiceReadTimeout,
                                TimeUnit.MILLISECONDS)));

        WebClient webClient = ApiClient.buildWebClientBuilder().clientConnector(
                new ReactorClientHttpConnector(httpClient)).baseUrl(notificationsServiceUri).build();

        return new it.pagopa.generated.notifications.v1.api.DefaultApi(new it.pagopa.generated.notifications.v1.ApiClient(webClient));
    }

    @Bean
    public  it.pagopa.generated.transactions.model.ObjectFactory objectFactoryNodeForPsp() {
        return new  it.pagopa.generated.transactions.model.ObjectFactory();
    }

    @Bean
    public  it.pagopa.generated.nodoperpsp.model.ObjectFactory objectFactoryNodoPerPSP() {
        return new  it.pagopa.generated.nodoperpsp.model.ObjectFactory();
    }
}
