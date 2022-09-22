package it.pagopa.transactions.projections.handlers;

import it.pagopa.generated.transactions.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdateData;
import it.pagopa.transactions.documents.TransactionAuthorizationStatusUpdatedEvent;
import it.pagopa.transactions.domain.*;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class AuthorizationUpdateProjectionHandlerTest {

    @InjectMocks
    private AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandler;

    @Mock
    private TransactionsViewRepository viewRepository;

    @Test
    void shouldHandleTransaction() {
        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(AuthorizationResultDto.OK)
                .authorizationCode("OK")
                .timestampOperation(OffsetDateTime.now());

        TransactionInitialized transaction = new TransactionInitialized(
                new TransactionId(UUID.randomUUID()),
                new PaymentToken("paymentToken"),
                new RptId("rptId"),
                new TransactionDescription("description"),
                new TransactionAmount(100),
                TransactionStatusDto.AUTHORIZATION_REQUESTED
        );

        it.pagopa.transactions.documents.Transaction expectedDocument = new it.pagopa.transactions.documents.Transaction(
                transaction.getTransactionId().value().toString(),
                transaction.getPaymentToken().value(),
                transaction.getRptId().value(),
                transaction.getDescription().value(),
                transaction.getAmount().value(),
                TransactionStatusDto.AUTHORIZED,
                transaction.getCreationDate()
        );

        TransactionAuthorizationStatusUpdateData statusUpdateData =
                new TransactionAuthorizationStatusUpdateData(
                        updateAuthorizationRequest.getAuthorizationResult(),
                        expectedDocument.getStatus()
                );

        TransactionAuthorizationStatusUpdatedEvent event = new TransactionAuthorizationStatusUpdatedEvent(
                transaction.getTransactionId().value().toString(),
                transaction.getRptId().value(),
                transaction.getPaymentToken().value(),
                statusUpdateData
        );

        TransactionInitialized expected = new TransactionInitialized(
                transaction.getTransactionId(),
                transaction.getPaymentToken(),
                transaction.getRptId(),
                transaction.getDescription(),
                transaction.getAmount(),
                ZonedDateTime.parse(expectedDocument.getCreationDate()),
                expectedDocument.getStatus()
        );

        /*
         * Preconditions
         */
        Mockito.when(viewRepository.findById(transaction.getTransactionId().value().toString()))
                .thenReturn(Mono.just(it.pagopa.transactions.documents.Transaction.from(transaction)));

        Mockito.when(viewRepository.save(expectedDocument)).thenReturn(Mono.just(expectedDocument));

        /*
         * Test
         */
        StepVerifier.create(authorizationUpdateProjectionHandler.handle(event))
                .expectNext(expected)
                .verifyComplete();

        /*
         * Assertions
         */
        Mockito.verify(viewRepository, Mockito.times(1)).save(argThat(savedTransaction -> savedTransaction.getStatus().equals(TransactionStatusDto.AUTHORIZED)));
    }
}
