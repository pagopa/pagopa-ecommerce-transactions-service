package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.projections.handlers.TransactionsActivationProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class TransactionsActivationProjectionHandlerTest {

    @InjectMocks
    private TransactionsActivationProjectionHandler handler;

    @Mock
    TransactionsViewRepository transactionsViewRepository;

    @Test
    void shouldSaveTransaction() {
        /** preconditions */

        String transactionIdString = UUID.randomUUID().toString();
        String rptIdString = "77777777777111111111111111111";
        String paymentTokenString = UUID.randomUUID().toString();
        String transactionDescription = "transaction description";
        int amountInt = 100;
        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail("jon.doe@email.it");
        transactionActivatedData.setPaymentNotices(
                Arrays.asList(
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
                Arrays.asList(
                        new it.pagopa.ecommerce.commons.domain.PaymentNotice(
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
                it.pagopa.ecommerce.commons.documents.Transaction.ClientId.CHECKOUT
        );

        it.pagopa.ecommerce.commons.documents.Transaction transactionDocument = it.pagopa.ecommerce.commons.documents.Transaction
                .from(transaction);

        Mockito.when(
                transactionsViewRepository.save(Mockito.any(it.pagopa.ecommerce.commons.documents.Transaction.class))
        ).thenReturn(Mono.just(transactionDocument));

        /** test */

        TransactionActivated transactionResult = handler.handle(event).block();

        Assert.assertNotEquals(transactionResult, transaction);
        Assert.assertEquals(transactionResult.getTransactionId(), transaction.getTransactionId());
        Assert.assertEquals(transactionResult.getStatus(), transaction.getStatus());
        Assert.assertEquals(
                transactionResult.getPaymentNotices().get(0).transactionAmount(),
                transaction.getPaymentNotices().get(0).transactionAmount()
        );
        Assert.assertEquals(
                transactionResult.getPaymentNotices().get(0).transactionDescription(),
                transaction.getPaymentNotices().get(0).transactionDescription()
        );
        Assert.assertEquals(
                transactionResult.getPaymentNotices().get(0).rptId(),
                transaction.getPaymentNotices().get(0).rptId()
        );
        Assert.assertEquals(
                transactionResult.getTransactionActivatedData().getPaymentNotices().get(0).getPaymentToken(),
                transaction.getTransactionActivatedData().getPaymentNotices().get(0).getPaymentToken()
        );

    }

}
