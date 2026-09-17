package it.pagopa.transactions.client;

import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils;
import it.pagopa.generated.wallet.v1.api.WalletsApi;
import it.pagopa.generated.wallet.v1.dto.WalletAuthDataDto;
import it.pagopa.generated.wallet.v1.dto.WalletNotificationRequestDto;
import it.pagopa.transactions.exceptions.BadGatewayException;
import it.pagopa.transactions.exceptions.WalletErrorResponseException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
                .doOnNext(
                        v -> LogTracingUtils.loggerTracingUtils()
                                .success()
                                .details(
                                        Map.of(
                                                "wallet_id",
                                                walletId
                                        )
                                )
                                .logInfo(log, "Retrieved wallet auth data")
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

    public Mono<Void> notifyWallet(
                                   String walletId,
                                   String orderId,
                                   WalletNotificationRequestDto walletNotificationRequestDto
    ) {
        return walletWebClient
                .notifyWalletInternal(UUID.fromString(walletId), orderId, walletNotificationRequestDto)
                .doOnNext(
                        ignored -> LogTracingUtils.loggerTracingUtils()
                                .success()
                                .dependency(LogTracingUtils.WALLET_DEPENDENCY)
                                .details(
                                        Map.of(
                                                "wallet_id",
                                                walletId,
                                                "operation_result",
                                                walletNotificationRequestDto.getOperationResult().getValue()
                                        )
                                )
                                .attributes(
                                        Map.of(
                                                LogTracingUtils.AttributeKeys.CTX_AUTHORIZATION_REQUEST_ID,
                                                orderId
                                        )
                                )
                                .logInfo(log, "POST notification performed successfully")
                )
                .doOnError(
                        WebClientResponseException.class,
                        WalletClient::logWebClientException
                )
                .onErrorMap(
                        exception -> {
                            Optional<HttpStatusCode> errorResponseCode = Optional
                                    .of(exception)
                                    .map(e -> {
                                        if (e instanceof WebClientResponseException webClientResponseException) {
                                            return webClientResponseException.getStatusCode();
                                        }
                                        return null;
                                    });
                            return new WalletErrorResponseException(
                                    "Error while invoke method for retrieve wallet info",
                                    errorResponseCode.orElse(null),
                                    exception
                            );
                        }
                );
    }

    private static void logWebClientException(WebClientResponseException e) {
        LogTracingUtils.loggerTracingUtils()
                .failure()
                .details(
                        Map.of(
                                "status_code",
                                Objects.toString(e.getStatusCode()),
                                "response_body",
                                e.getResponseBodyAsString()
                        )
                )
                .logError(log, e, "Got bad response from wallet-service");
    }
}
