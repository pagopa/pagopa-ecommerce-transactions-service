package it.pagopa.transactions.configuration;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.xml.Jaxb2XmlDecoder;
import org.springframework.http.codec.xml.Jaxb2XmlEncoder;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import it.pagopa.ecommerce.sessions.v1.ApiClient;
import it.pagopa.ecommerce.sessions.v1.api.DefaultApi;
import it.pagopa.nodeforpsp.ObjectFactory;
import it.pagopa.transactions.utils.soap.Jaxb2SoapEncoder;
import reactor.netty.http.client.HttpClient;

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
            clientCodecConfigurer.customCodecs().registerWithDefaultConfig(new Jaxb2SoapEncoder());
            // clientCodecConfigurer.customCodecs().registerWithDefaultConfig(new Jaxb2SoapDecoder());
        }).build();

        return WebClient.builder().baseUrl(nodoUri)
        // .defaultHeader("Content-Type", "text/xml")
                .clientConnector(new ReactorClientHttpConnector(httpClient)).exchangeStrategies(exchangeStrategies)
                .build();
    }

    @Bean(name = "ecommerceSessionsWebClient")
    public DefaultApi ecommerceSessionsWebClient(@Value("${ecommerceSessions.uri}") String ecommerceSessionsUri,
                                                 @Value("${ecommerceSessions.readTimeout}") int ecommerceSessionsReadTimeout,
                                                 @Value("${ecommerceSessions.connectionTimeout}") int ecommerceSessionsConnectionTimeout) {

        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, ecommerceSessionsConnectionTimeout)
                .doOnConnected(connection -> connection
                        .addHandlerLast(new ReadTimeoutHandler(ecommerceSessionsReadTimeout, TimeUnit.MILLISECONDS)));

        WebClient webClient = ApiClient.buildWebClientBuilder()
                .clientConnector(new ReactorClientHttpConnector(httpClient)).baseUrl(ecommerceSessionsUri).build();

        return new DefaultApi(new ApiClient(webClient));
    }

    @Bean
    public ObjectFactory objectFactory() {
        return new ObjectFactory();
    }
}
