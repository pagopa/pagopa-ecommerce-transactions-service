package it.pagopa.transactions.client;

import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.generated.wallet.v1.api.WalletsApi;
import it.pagopa.generated.wallet.v1.dto.WalletAuthCardDataDto;
import it.pagopa.generated.wallet.v1.dto.WalletAuthDataDto;
import it.pagopa.generated.wallet.v1.dto.WalletNotificationRequestCardDetailsDto;
import it.pagopa.generated.wallet.v1.dto.WalletNotificationRequestDto;
import it.pagopa.transactions.exceptions.BadGatewayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class WalletClientTest {

    @InjectMocks
    private WalletClient walletClient;

    @Mock
    private WalletsApi walletsApi;

    @Test
    void shouldReturnWalletInfo() {
        UUID WALLET_ID = UUID.randomUUID();

        WalletAuthDataDto walletTestResponseDto = new WalletAuthDataDto()
                .walletId(WALLET_ID)
                .brand("brand")
                .contractId("contractId")
                .paymentMethodData(new WalletAuthCardDataDto().bin("bin"));

        when(walletsApi.getWalletAuthDataById(WALLET_ID))
                .thenReturn(Mono.just(walletTestResponseDto));

        StepVerifier
                .create(
                        walletClient.getWalletInfo(WALLET_ID.toString())
                )
                .expectNextMatches(
                        response -> response.equals(walletTestResponseDto)

                )
                .verifyComplete();

    }

    @Test
    void shouldReturnErrorFromRetrieveWalletInfo() {
        UUID WALLET_ID = UUID.randomUUID();
        /**
         * preconditions
         */
        when(walletsApi.getWalletAuthDataById(WALLET_ID))
                .thenReturn(
                        Mono.error(
                                new WebClientResponseException(
                                        HttpStatus.NOT_FOUND.value(),
                                        "SessionId not found",
                                        null,
                                        null,
                                        null
                                )
                        )
                );

        /**
         * test
         */
        StepVerifier.create(walletClient.getWalletInfo(WALLET_ID.toString()))
                .expectErrorMatches(
                        e -> e instanceof BadGatewayException
                )
                .verify();
    }

    @Test
    void shouldPerformWalletNotifyOperationSuccessfully() {
        String walletId = TransactionTestUtils.NPG_WALLET_ID;
        String orderId = TransactionTestUtils.NPG_ORDER_ID;
        String securityToken = "securityToken";
        WalletNotificationRequestDto request = new WalletNotificationRequestDto()
                .operationId("operationId")
                .timestampOperation(OffsetDateTime.now())
                .operationResult(WalletNotificationRequestDto.OperationResultEnum.FAILED)
                .errorCode("errorCode")
                .details(
                        new WalletNotificationRequestCardDetailsDto()
                                .type("CARDS")
                                .paymentInstrumentGatewayId("cardId4")
                );

        when(walletsApi.notifyWallet(any(), any(), any(), any()))
                .thenReturn(Mono.empty());

        StepVerifier
                .create(
                        walletClient.notifyWallet(
                                walletId,
                                orderId,
                                securityToken,
                                request
                        )
                )
                .verifyComplete();
        verify(walletsApi, times(1)).notifyWallet(UUID.fromString(walletId), orderId, securityToken, request);
    }

    @Test
    void shouldHandlerErrorPerformingWalletNotificationRequest() {
        String walletId = TransactionTestUtils.NPG_WALLET_ID;
        String orderId = TransactionTestUtils.NPG_ORDER_ID;
        String securityToken = "securityToken";
        WalletNotificationRequestDto request = new WalletNotificationRequestDto()
                .operationId("operationId")
                .timestampOperation(OffsetDateTime.now())
                .operationResult(WalletNotificationRequestDto.OperationResultEnum.FAILED)
                .errorCode("errorCode")
                .details(
                        new WalletNotificationRequestCardDetailsDto()
                                .type("CARDS")
                                .paymentInstrumentGatewayId("cardId4")
                );

        when(walletsApi.notifyWallet(any(), any(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("error communicating with wallet")));

        StepVerifier
                .create(
                        walletClient.notifyWallet(
                                walletId,
                                orderId,
                                securityToken,
                                request
                        )
                ).expectError(BadGatewayException.class)
                .verify();
        verify(walletsApi, times(1)).notifyWallet(UUID.fromString(walletId), orderId, securityToken, request);
    }
}
