package it.pagopa.transactions.configuration;

import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import it.pagopa.nodeforpsp.ObjectFactory;
import it.pagopa.transactions.utils.soap.Jaxb2SoapDecoder;
import it.pagopa.transactions.utils.soap.Jaxb2SoapEncoder;
import reactor.netty.http.client.HttpClient;

@Configuration
public class WebClientsConfig {

    @Value("${nodo.uri}")
    private String nodoUri;

    @Value("${nodo.readTimeout}")
    private int nodoReadTimeout;

    @Value("${nodo.connectionTimeout}")
    private int nodoConnectionTimeout;

    @Bean(name = "nodoWebClient")
    public WebClient nodoWebClient() {

        HttpClient httpClient = HttpClient.create().option(ChannelOption.CONNECT_TIMEOUT_MILLIS, nodoConnectionTimeout)
                .doOnConnected(connection -> connection
                        .addHandlerLast(new ReadTimeoutHandler(nodoReadTimeout, TimeUnit.MILLISECONDS)));

        ExchangeStrategies exchangeStrategies = ExchangeStrategies.builder().codecs(clientCodecConfigurer -> {
            clientCodecConfigurer.customCodecs().register(new Jaxb2SoapEncoder());
            clientCodecConfigurer.customCodecs().register(new Jaxb2SoapDecoder());
        }).build();

        return WebClient.builder().baseUrl(nodoUri).defaultHeader("Content-Type", "text/xml")
                .clientConnector(new ReactorClientHttpConnector(httpClient)).exchangeStrategies(exchangeStrategies)
                .build();
    }

    @Bean
    public ObjectFactory objectFactory() {
        return new ObjectFactory();
    }
}
