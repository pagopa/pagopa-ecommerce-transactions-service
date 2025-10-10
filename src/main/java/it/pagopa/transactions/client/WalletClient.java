package it.pagopa.transactions.client;

import it.pagopa.generated.wallet.v1.api.WalletsApi;
import it.pagopa.generated.wallet.v1.dto.WalletAuthDataDto;
import it.pagopa.generated.wallet.v1.dto.WalletNotificationRequestDto;
import it.pagopa.transactions.exceptions.BadGatewayException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Component
@Slf4j
public class WalletClient {

    private WalletsApi walletWebClient;

    @Autowired
    public WalletClient(@Qualifier("walletWebClient") WalletsApi walletWebClient) {
        this.walletWebClient = walletWebClient;
    }

    public Mono<WalletAuthDataDto> getWalletInfo(
                                                 String walletId

    ) {
        return walletWebClient
                .getWalletAuthDataById(UUID.fromString(walletId))
                .doOnError(
                        WebClientResponseException.class,
                        WalletClient::logWebClientException
                )
                .onErrorMap(
                        err -> new BadGatewayException(
                                "Error while invoke method for retrieve wallet info",
                                HttpStatus.BAD_GATEWAY
                        )
                );
    }

    public Mono<Void> notifyWallet(
                                   String walletId,
                                   String orderId,
                                   WalletNotificationRequestDto walletNotificationRequestDto
    ) {
        log.info(
                "Performing wallet POST notification for walletId: [{}] with operation result: [{}]",
                walletId,
                walletNotificationRequestDto.getOperationResult()
        );
        return walletWebClient
                .notifyWalletInternal(UUID.fromString(walletId), orderId, walletNotificationRequestDto)
                .doOnNext(
                        (ignored) -> log.info("POST notification performed successfully for walletId: [{}]", walletId)
                )
                .doOnError(
                        WebClientResponseException.class,
                        WalletClient::logWebClientException
                )
                .onErrorMap(
                        err -> new BadGatewayException(
                                "Error while invoke method for retrieve wallet info",
                                HttpStatus.BAD_GATEWAY
                        )
                );
    }

    private static void logWebClientException(WebClientResponseException e) {
        log.info(
                "Got bad response from wallet-service [HTTP {}]: {}",
                e.getStatusCode(),
                e.getResponseBodyAsString()
        );
    }
}
