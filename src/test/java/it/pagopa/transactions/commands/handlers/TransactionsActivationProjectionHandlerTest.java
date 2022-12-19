package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.NoticeCode;
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

    @Mock TransactionsViewRepository transactionsViewRepository;

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
        transactionActivatedData.setNoticeCodes( Arrays.asList(new NoticeCode(
                paymentTokenString,rptIdString,transactionDescription,amountInt)));

        TransactionActivatedEvent event = new TransactionActivatedEvent(
                transactionIdString,
                Arrays.asList(new NoticeCode(paymentTokenString, rptIdString,null,null)),
                transactionActivatedData);

        TransactionActivatedData data = event.getData();
        TransactionId transactionId = new TransactionId(UUID.fromString(event.getTransactionId()));
        PaymentToken paymentToken = new PaymentToken(event.getNoticeCodes().get(0).getPaymentToken());
        RptId rptId = new RptId(event.getNoticeCodes().get(0).getRptId());
        TransactionDescription description = new TransactionDescription(data.getNoticeCodes().get(0).getDescription());
        TransactionAmount amount = new TransactionAmount(data.getNoticeCodes().get(0).getAmount());
        Email email = new Email("foo@example.com");
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";

        TransactionActivated transaction =
                new TransactionActivated(transactionId,
                        Arrays.asList(new it.pagopa.ecommerce.commons.domain.NoticeCode(paymentToken, rptId, amount, description)),
                        email, faultCode, faultCodeString, TransactionStatusDto.ACTIVATED);

        it.pagopa.ecommerce.commons.documents.Transaction transactionDocument =
                it.pagopa.ecommerce.commons.documents.Transaction.from(transaction);

        Mockito.when(transactionsViewRepository.save(Mockito.any(it.pagopa.ecommerce.commons.documents.Transaction.class))).thenReturn(Mono.just(transactionDocument));

        /** test */

        TransactionActivated transactionResult = handler.handle(event).block();

        Assert.assertNotEquals(transactionResult, transaction);
        Assert.assertEquals(transactionResult.getTransactionId(), transaction.getTransactionId());
        Assert.assertEquals(transactionResult.getStatus(), transaction.getStatus());
        Assert.assertEquals(transactionResult.getNoticeCodes().get(0).transactionAmount(),transaction.getNoticeCodes().get(0).transactionAmount());
        Assert.assertEquals(transactionResult.getNoticeCodes().get(0).transactionDescription(),transaction.getNoticeCodes().get(0).transactionDescription());
        Assert.assertEquals(transactionResult.getNoticeCodes().get(0).rptId(),transaction.getNoticeCodes().get(0).rptId());
        Assert.assertEquals(transactionResult.getTransactionActivatedData().getNoticeCodes().get(0).getPaymentToken(),transaction.getTransactionActivatedData().getNoticeCodes().get(0).getPaymentToken());


    }

}
