package it.pagopa.transactions.configurations;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import it.pagopa.ecommerce.commons.client.JwtIssuerClient;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.ApiClient;
import it.pagopa.ecommerce.commons.generated.jwtissuer.v1.api.JwtIssuerApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.util.concurrent.TimeUnit;

@Configuration
public class JWTIssuerWebClientConfig {

    @Bean(name = "jwtIssuerWebClient")
    public JwtIssuerApi jwtIssuerWebClient(
                                           @Value("${jwtissuer.uri}") String jwtIssuerWebClientUri,
                                           @Value(
                                               "${jwtissuer.readTimeout}"
                                           ) int jwtIssuerWebClientReadTimeout,
                                           @Value(
                                               "${jwtissuer.connectionTimeout}"
                                           ) int jwtIssuerWebClientConnectionTimeout,
                                           @Value(
                                               "${jwtissuer.apiKey}"
                                           ) String jwtIssuerApiKey
    ) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, jwtIssuerWebClientConnectionTimeout)
                .doOnConnected(
                        connection -> connection.addHandlerLast(
                                new ReadTimeoutHandler(
                                        jwtIssuerWebClientReadTimeout,
                                        TimeUnit.MILLISECONDS
                                )
                        )
                );

        WebClient webClient = ApiClient.buildWebClientBuilder().clientConnector(
                new ReactorClientHttpConnector(httpClient)
        ).defaultHeader("x-api-key", jwtIssuerApiKey).baseUrl(jwtIssuerWebClientUri).build();

        return new JwtIssuerApi(new ApiClient(webClient).setBasePath(jwtIssuerWebClientUri));
    }

    @Bean
    public JwtIssuerClient jwtIssuerClient(
                                           JwtIssuerApi jwtIssuerApi
    ) {
        return new JwtIssuerClient(jwtIssuerApi);
    }

}
