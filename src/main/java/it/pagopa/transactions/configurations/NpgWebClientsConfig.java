package it.pagopa.transactions.configurations;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import it.pagopa.ecommerce.commons.client.NpgClient;
import it.pagopa.ecommerce.commons.generated.npg.v1.ApiClient;
import it.pagopa.ecommerce.commons.generated.npg.v1.api.PaymentServicesApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

@Configuration
public class NpgWebClientsConfig implements WebFluxConfigurer {

    @Value("${npg.client.maxInMemory}")
    private int maxMemorySize;

    @Bean(name = "npgWebClient")
    public PaymentServicesApi npgWebClient(
                                           @Value("${npg.uri}") String afmWebClientUri,
                                           @Value(
                                               "${npg.readTimeout}"
                                           ) int afmWebClientReadTimeout,
                                           @Value(
                                               "${npg.connectionTimeout}"
                                           ) int afmWebClientConnectionTimeout
    ) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, afmWebClientConnectionTimeout)
                .doOnConnected(
                        connection -> connection.addHandlerLast(
                                new ReadTimeoutHandler(
                                        afmWebClientReadTimeout,
                                        TimeUnit.MILLISECONDS
                                )
                        )
                );

        WebClient webClient = ApiClient.buildWebClientBuilder().exchangeStrategies(
                ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(maxMemorySize))
                        .build()
        ).clientConnector(
                new ReactorClientHttpConnector(httpClient)
        ).baseUrl(afmWebClientUri).build();

        return new PaymentServicesApi(new ApiClient(webClient));
    }

    @Bean
    public NpgClient npgClient(
                               PaymentServicesApi hostedFieldsApi,
                               @Value("${npg.client.key}") String npgKey
    ) {
        return new NpgClient(hostedFieldsApi, npgKey);
    }

}
