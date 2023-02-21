package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.v1.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.transactions.projections.handlers.TransactionsActivationProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@ExtendWith(MockitoExtension.class)
class TransactionsActivationProjectionHandlerTest {

    @InjectMocks
    private TransactionsActivationProjectionHandler handler;

    @Mock
    TransactionsViewRepository transactionsViewRepository;

    @Test
    void shouldSaveTransaction() {
        /* preconditions */

        String transactionIdString = UUID.randomUUID().toString();
        String rptIdString = "77777777777111111111111111111";
        String paymentTokenString = UUID.randomUUID().toString();
        String transactionDescription = "transaction description";
        int amountInt = 100;
        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail("jon.doe@email.it");
        transactionActivatedData.setPaymentNotices(
                List.of(
                        new PaymentNotice(
                                paymentTokenString,
                                rptIdString,
                                transactionDescription,
                                amountInt,
                                null
                        )
                )
        );

        TransactionActivatedEvent event = new TransactionActivatedEvent(
                transactionIdString,
                transactionActivatedData
        );

        TransactionActivatedData data = event.getData();
        TransactionId transactionId = new TransactionId(UUID.fromString(event.getTransactionId()));
        PaymentToken paymentToken = new PaymentToken(event.getData().getPaymentNotices().get(0).getPaymentToken());
        RptId rptId = new RptId(event.getData().getPaymentNotices().get(0).getRptId());
        TransactionDescription description = new TransactionDescription(
                data.getPaymentNotices().get(0).getDescription()
        );
        TransactionAmount amount = new TransactionAmount(data.getPaymentNotices().get(0).getAmount());
        Email email = new Email("foo@example.com");
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new it.pagopa.ecommerce.commons.domain.v1.PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode
                        )
                ),
                email,
                faultCode,
                faultCodeString,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT
        );

        it.pagopa.ecommerce.commons.documents.v1.Transaction transactionDocument = it.pagopa.ecommerce.commons.documents.v1.Transaction
                .from(transaction);

        Mockito.when(
                transactionsViewRepository.save(Mockito.any(it.pagopa.ecommerce.commons.documents.v1.Transaction.class))
        ).thenReturn(Mono.just(transactionDocument));

        /* test */

        TransactionActivated transactionResult = handler.handle(event).block();

        assertNotEquals(transactionResult, transaction);
        assertEquals(transactionResult.getTransactionId(), transaction.getTransactionId());
        assertEquals(transactionResult.getStatus(), transaction.getStatus());
        assertEquals(
                transactionResult.getPaymentNotices().get(0).transactionAmount(),
                transaction.getPaymentNotices().get(0).transactionAmount()
        );
        assertEquals(
                transactionResult.getPaymentNotices().get(0).transactionDescription(),
                transaction.getPaymentNotices().get(0).transactionDescription()
        );
        assertEquals(
                transactionResult.getPaymentNotices().get(0).rptId(),
                transaction.getPaymentNotices().get(0).rptId()
        );
        assertEquals(
                transactionResult.getTransactionActivatedData().getPaymentNotices().get(0).getPaymentToken(),
                transaction.getTransactionActivatedData().getPaymentNotices().get(0).getPaymentToken()
        );

    }

}
