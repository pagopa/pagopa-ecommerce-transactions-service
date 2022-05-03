package it.pagopa.transactions.client;

import java.net.ConnectException;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.netty.handler.timeout.TimeoutException;
import it.pagopa.ecommerce.sessions.v1.api.DefaultApi;
import it.pagopa.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.ecommerce.sessions.v1.dto.SessionTokenDto;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

@Component
public class EcommerceSessionsClient {

    @Autowired
    private DefaultApi ecommerceSessionsWebClient;

    public Mono<SessionTokenDto> createSessionToken(SessionDataDto request) {

        return ecommerceSessionsWebClient.postToken(request).retryWhen(Retry.backoff(2, Duration.ofMillis(25))
                .filter(throwable -> throwable instanceof TimeoutException || throwable instanceof ConnectException));
    }
}
