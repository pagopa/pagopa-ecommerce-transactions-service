package it.pagopa.transactions.projections.handlers.v2;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.ZonedDateTime;
import java.util.*;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthorizationRequestProjectionHandlerTest {

    private it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler authorizationRequestProjectionHandler;

    @Mock
    private TransactionsViewRepository transactionsViewRepository;

    private Transaction initialDocument;
    private AuthorizationRequestData authorizationData;
    private Integer fee;

    @BeforeEach
    void setup() {
        initialDocument = TransactionTestUtils.transactionDocument(
                TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );
        fee = 50;
        authorizationData = createAuthorizationRequestData(initialDocument, fee);
    }

    @Test
    void shouldUpdateTransactionWithAuthorizationRequestedStatus() {

        authorizationRequestProjectionHandler = new AuthorizationRequestProjectionHandler(
                transactionsViewRepository,
                true
        );

        Transaction expectedDocument = createExpectedUpdatedTransaction(initialDocument, fee);

        when(transactionsViewRepository.findById(initialDocument.getTransactionId()))
                .thenReturn(Mono.just(initialDocument));

        when(transactionsViewRepository.save(expectedDocument))
                .thenReturn(Mono.just(expectedDocument));

        StepVerifier.create(authorizationRequestProjectionHandler.handle(authorizationData))
                .expectNext(expectedDocument)
                .verifyComplete();

        verify(transactionsViewRepository).save(expectedDocument);
    }

    @Test
    void shouldReturnUpdatedTransactionWithoutSavingWhenUpdateDisabled() {

        authorizationRequestProjectionHandler = new AuthorizationRequestProjectionHandler(
                transactionsViewRepository,
                false
        );

        when(transactionsViewRepository.findById(initialDocument.getTransactionId()))
                .thenReturn(Mono.just(initialDocument));

        StepVerifier.create(authorizationRequestProjectionHandler.handle(authorizationData))
                .expectNextMatches(
                        transaction -> transaction.getStatus().equals(TransactionStatusDto.AUTHORIZATION_REQUESTED) &&
                                transaction.getFeeTotal().equals(fee) &&
                                transaction.getPspId().equals(TransactionTestUtils.PSP_ID)
                )
                .verifyComplete();

        verify(transactionsViewRepository, never()).save(any());
    }

    private AuthorizationRequestData createAuthorizationRequestData(
                                                                    Transaction transaction,
                                                                    int fee
    ) {
        return new AuthorizationRequestData(
                new TransactionId(transaction.getTransactionId()),
                null,
                transaction.getEmail(),
                fee,
                null,
                TransactionTestUtils.PSP_ID,
                TransactionTestUtils.PAYMENT_TYPE_CODE,
                "brokerName",
                "pspChannelCode",
                "CARDS",
                "paymentMethodDescription",
                "pspBusinessName",
                false,
                transaction.getPaymentGateway(),
                Optional.of(UUID.randomUUID().toString()),
                Optional.empty(),
                "VISA",
                null,
                "http://asset",
                Optional.of(Map.of("VISA", "http://visaAsset")),
                UUID.randomUUID().toString()
        );
    }

    private Transaction createExpectedUpdatedTransaction(
                                                         Transaction original,
                                                         int fee
    ) {
        return new Transaction(
                original.getTransactionId(),
                original.getPaymentNotices(),
                fee,
                original.getEmail(),
                TransactionStatusDto.AUTHORIZATION_REQUESTED,
                original.getClientId(),
                original.getCreationDate(),
                original.getIdCart(),
                original.getRrn(),
                original.getUserId(),
                TransactionTestUtils.PAYMENT_TYPE_CODE,
                TransactionTestUtils.PSP_ID
        );
    }

}
