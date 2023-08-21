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
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

@Configuration
public class NpgWebClientsConfig {

    @Bean(name = "npgWebClient")
    public PaymentServicesApi npgWebClient(
                                           @Value("${npg.uri}") String npgWebClientUri,
                                           @Value(
                                               "${npg.readTimeout}"
                                           ) int npgWebClientReadTimeout,
                                           @Value(
                                               "${npg.connectionTimeout}"
                                           ) int npgWebClientConnectionTimeout
    ) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, npgWebClientConnectionTimeout)
                .doOnConnected(
                        connection -> connection.addHandlerLast(
                                new ReadTimeoutHandler(
                                        npgWebClientReadTimeout,
                                        TimeUnit.MILLISECONDS
                                )
                        )
                );

        WebClient webClient = ApiClient.buildWebClientBuilder().clientConnector(
                new ReactorClientHttpConnector(httpClient)
        ).baseUrl(npgWebClientUri).build();

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
