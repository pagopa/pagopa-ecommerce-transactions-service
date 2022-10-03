package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.transactions.documents.TransactionActivatedData;
import it.pagopa.transactions.documents.TransactionActivatedEvent;
import it.pagopa.transactions.domain.*;
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

import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class TransactionsActivationProjectionHandlerTest {


    @InjectMocks
    private TransactionsActivationProjectionHandler handler;

    @Mock TransactionsViewRepository transactionsViewRepository;

    @Test
    void shouldSaveTransaction() {
        /** preconditions */

        String transactionIdString = UUID.randomUUID().toString();
        String rptIdString = "RtpID";
        String paymentTokenString = UUID.randomUUID().toString();
        String transactionDescription = "transaction description";
        int amountInt = 100;
        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail("jon.doe@email.it");
        transactionActivatedData.setAmount(amountInt);
        transactionActivatedData.setDescription(transactionDescription);

        TransactionActivatedEvent event = new TransactionActivatedEvent(transactionIdString, rptIdString, paymentTokenString, transactionActivatedData);

        TransactionActivatedData data = event.getData();
        TransactionId transactionId = new TransactionId(UUID.fromString(event.getTransactionId()));
        PaymentToken paymentToken = new PaymentToken(event.getPaymentToken());
        RptId rptId = new RptId(event.getRptId());
        TransactionDescription description = new TransactionDescription(data.getDescription());
        TransactionAmount amount = new TransactionAmount(data.getAmount());
        Email email = new Email("foo@example.com");

        TransactionActivated transaction =
                new TransactionActivated(transactionId, paymentToken, rptId, description, amount, email, TransactionStatusDto.ACTIVATED);

        it.pagopa.transactions.documents.Transaction transactionDocument =
                it.pagopa.transactions.documents.Transaction.from(transaction);

        Mockito.when(transactionsViewRepository.save(Mockito.any(it.pagopa.transactions.documents.Transaction.class))).thenReturn(Mono.just(transactionDocument));

        /** test */

        TransactionActivated transactionResult = handler.handle(event).block();

        Assert.assertNotEquals(transactionResult, transaction);
        Assert.assertEquals(transactionResult.getTransactionId(),transaction.getTransactionId());
        Assert.assertEquals(transactionResult.getStatus(),transaction.getStatus());
        Assert.assertEquals(transactionResult.getAmount(),transaction.getAmount());
        Assert.assertEquals(transactionResult.getDescription(),transaction.getDescription());
        Assert.assertEquals(transactionResult.getRptId(),transaction.getRptId());
        Assert.assertEquals(transactionResult.getTransactionActivatedData().getPaymentToken(),transaction.getTransactionActivatedData().getPaymentToken());


    }

}
