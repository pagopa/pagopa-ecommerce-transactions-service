package it.pagopa.transactions.commands.handlers.v2;

import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.PaymentTransferInformation;
import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.documents.v2.activation.NpgTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.v2.TransactionActivated;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler;
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

        String transactionIdString = TransactionTestUtils.TRANSACTION_ID;
        String paFiscalCode = "77777777777";
        String rptIdString = paFiscalCode + "111111111111111111";
        String paymentTokenString = UUID.randomUUID().toString();
        String transactionDescription = "transaction description";
        String orderId = "orderId";
        int amountInt = 100;
        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail(TransactionTestUtils.EMAIL);
        transactionActivatedData
                .setTransactionGatewayActivationData(new NpgTransactionGatewayActivationData(orderId, null, null));
        transactionActivatedData.setPaymentNotices(
                List.of(
                        new PaymentNotice(
                                paymentTokenString,
                                rptIdString,
                                transactionDescription,
                                amountInt,
                                null,
                                List.of(new PaymentTransferInformation(paFiscalCode, false, amountInt, null)),
                                false
                        )
                )
        );

        TransactionActivatedEvent event = new TransactionActivatedEvent(
                transactionIdString,
                transactionActivatedData
        );

        TransactionActivatedData data = event.getData();
        TransactionId transactionId = new TransactionId(event.getTransactionId());
        PaymentToken paymentToken = new PaymentToken(event.getData().getPaymentNotices().get(0).getPaymentToken());
        RptId rptId = new RptId(event.getData().getPaymentNotices().get(0).getRptId());
        TransactionDescription description = new TransactionDescription(
                data.getPaymentNotices().get(0).getDescription()
        );
        TransactionAmount amount = new TransactionAmount(data.getPaymentNotices().get(0).getAmount());
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        String idCart = "idCart";
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                List.of(
                                        new PaymentTransferInfo(
                                                rptIdString.substring(0, 11),
                                                false,
                                                amount.value(),
                                                null
                                        )
                                ),
                                false
                        )
                ),
                email,
                faultCode,
                faultCodeString,
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new NpgTransactionGatewayActivationData(orderId, null, null)
        );

        it.pagopa.ecommerce.commons.documents.v2.Transaction transactionDocument = it.pagopa.ecommerce.commons.documents.v2.Transaction
                .from(transaction);

        Mockito.when(
                transactionsViewRepository.save(Mockito.any(it.pagopa.ecommerce.commons.documents.v2.Transaction.class))
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
        assertEquals(
                ((NpgTransactionGatewayActivationData) transactionResult.getTransactionActivatedData()
                        .getTransactionGatewayActivationData()).getOrderId(),
                ((NpgTransactionGatewayActivationData) transaction.getTransactionActivatedData()
                        .getTransactionGatewayActivationData()).getOrderId()
        );

    }

}
