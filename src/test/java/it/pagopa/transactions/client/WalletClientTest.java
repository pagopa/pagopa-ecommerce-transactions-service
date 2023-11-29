package it.pagopa.transactions.client;

import it.pagopa.generated.wallet.v1.api.WalletsApi;
import it.pagopa.generated.wallet.v1.dto.WalletInfoDto;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import java.util.UUID;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class WalletClientTest {

    @InjectMocks
    private WalletClient walletClient;

    @Mock
    private WalletsApi walletsApi;

    @Test
    void shouldReturnWalletInfo() {
        UUID WALLET_ID = UUID.randomUUID();

        WalletInfoDto walletTestResponseDto = new WalletInfoDto()
                .bin("bin")
                .walletId(WALLET_ID)
                .brand("brand")
                .contractId("contractId");

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
                        e -> e instanceof InvalidRequestException invalidRequestException
                                && invalidRequestException.getMessage()
                                        .equals("Error while invoke method for retrieve wallet info")
                )
                .verify();
    }
}
