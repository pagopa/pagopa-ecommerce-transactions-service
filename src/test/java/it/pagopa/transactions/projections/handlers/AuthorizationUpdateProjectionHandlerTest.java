package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.TransactionAuthorizationStatusUpdateData;
import it.pagopa.ecommerce.commons.documents.TransactionAuthorizationStatusUpdatedEvent;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
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
import java.util.Arrays;
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

        TransactionActivated transaction = new TransactionActivated(
                new TransactionId(UUID.randomUUID()),
                Arrays.asList(
                        new PaymentNotice(
                                new PaymentToken("paymentToken"),
                                new RptId("77777777777111111111111111111"),
                                new TransactionAmount(100),
                                new TransactionDescription("description"),
                                new PaymentContextCode(null)
                        )
                ),
                new Email("email@example.com"),
                "faultCode",
                "faultCodeString",
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_REQUESTED,
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
        );

        it.pagopa.ecommerce.commons.documents.Transaction expectedDocument = new it.pagopa.ecommerce.commons.documents.Transaction(
                transaction.getTransactionId().value().toString(),
                transaction.getTransactionActivatedData().getPaymentNotices().get(0).getPaymentToken(),
                transaction.getTransactionActivatedData().getPaymentNotices().get(0).getRptId(),
                transaction.getTransactionActivatedData().getPaymentNotices().get(0).getDescription(),
                transaction.getTransactionActivatedData().getPaymentNotices().get(0).getAmount(),
                transaction.getEmail().value(),
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZED,
                transaction.getCreationDate()
        );

        TransactionAuthorizationStatusUpdateData statusUpdateData = new TransactionAuthorizationStatusUpdateData(
                it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto
                        .fromValue(updateAuthorizationRequest.getAuthorizationResult().toString()),
                expectedDocument.getStatus(),
                updateAuthorizationRequest.getAuthorizationCode()
        );

        TransactionAuthorizationStatusUpdatedEvent event = new TransactionAuthorizationStatusUpdatedEvent(
                transaction.getTransactionId().value().toString(),
                statusUpdateData
        );

        TransactionActivated expected = new TransactionActivated(
                transaction.getTransactionId(),
                transaction.getPaymentNotices().stream()
                        .map(
                                PaymentNotice -> new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                        PaymentNotice.paymentToken(),
                                        PaymentNotice.rptId(),
                                        PaymentNotice.transactionAmount(),
                                        PaymentNotice.transactionDescription(),
                                        PaymentNotice.paymentContextCode()
                                )
                        ).toList(),
                transaction.getEmail(),
                null,
                null,
                ZonedDateTime.parse(expectedDocument.getCreationDate()),
                expectedDocument.getStatus(),
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.UNKNOWN
        );

        /*
         * Preconditions
         */
        Mockito.when(viewRepository.findById(transaction.getTransactionId().value().toString()))
                .thenReturn(Mono.just(it.pagopa.ecommerce.commons.documents.Transaction.from(transaction)));

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
        Mockito.verify(viewRepository, Mockito.times(1)).save(
                argThat(savedTransaction -> savedTransaction.getStatus().equals(TransactionStatusDto.AUTHORIZED))
        );
    }
}
