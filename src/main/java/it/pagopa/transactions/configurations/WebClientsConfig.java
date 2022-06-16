package it.pagopa.transactions.configurations;

import java.util.concurrent.TimeUnit;

import it.pagopa.generated.ecommerce.sessions.v1.api.DefaultApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import it.pagopa.generated.ecommerce.sessions.v1.ApiClient;
import it.pagopa.generated.transactions.model.ObjectFactory;
import it.pagopa.transactions.utils.soap.Jaxb2SoapDecoder;
import it.pagopa.transactions.utils.soap.Jaxb2SoapEncoder;
import reactor.netty.http.client.HttpClient;

import it.pagopa.generated.ecommerce.gateway.v1.api.PaymentTransactionsControllerApi;

@Configuration
public class WebClientsConfig {

    @Bean(name = "nodoWebClient")
    public WebClient nodoWebClient(@Value("${nodo.uri}") String nodoUri,
                                   @Value("${nodo.readTimeout}") int nodoReadTimeout,
                                   @Value("${nodo.connectionTimeout}") int nodoConnectionTimeout) {

        HttpClient httpClient = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, nodoConnectionTimeout)
                .doOnConnected(connection -> connection
                        .addHandlerLast(new ReadTimeoutHandler(nodoReadTimeout, TimeUnit.MILLISECONDS)));

        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder().codecs(clientCodecConfigurer -> {
            clientCodecConfigurer.registerDefaults(false);
            clientCodecConfigurer.customCodecs().register(new Jaxb2SoapDecoder());
            clientCodecConfigurer.customCodecs().register(new Jaxb2SoapEncoder());
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

        WebClient webClient = it.pagopa.generated.ecommerce.gateway.v1.ApiClient.buildWebClientBuilder().clientConnector(
                new ReactorClientHttpConnector(httpClient)).baseUrl(paymentTransactionGatewayUri).build();

        return new PaymentTransactionsControllerApi(new it.pagopa.generated.ecommerce.gateway.v1.ApiClient(webClient));
    }

    @Bean
    public ObjectFactory objectFactory() {
        return new ObjectFactory();
    }
}
