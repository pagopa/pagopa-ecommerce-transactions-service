package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.TransactionActivationRequestedData;
import it.pagopa.ecommerce.commons.documents.TransactionActivationRequestedEvent;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class TransactionProjectionHandlerTest {

    @InjectMocks
    private TransactionsActivationRequestedProjectionHandler transactionsProjectionHandler;

    @Mock
    private TransactionsViewRepository viewEventStoreRepository;

    @Test
    void shouldHandleTransaction() {
        UUID transactionUUID = UUID.randomUUID();

        TransactionActivationRequestedData transactionActivationRequestedData = new TransactionActivationRequestedData();
        transactionActivationRequestedData.setEmail("email@test.it");
        PaymentNotice paymentNotice = new PaymentNotice(null, "77777777777302016723749670035", "reason", 1, null);
        transactionActivationRequestedData.setPaymentNotices(Arrays.asList(paymentNotice));
        transactionActivationRequestedData.setFaultCode("faultCode");
        transactionActivationRequestedData.setFaultCodeString("faultCodeString");

        TransactionActivationRequestedEvent transactionActivationRequestedEvent = new TransactionActivationRequestedEvent(
                transactionUUID.toString(),
                transactionActivationRequestedData
        );

        TransactionActivationRequestedEvent event = new TransactionActivationRequestedEvent(
                transactionActivationRequestedEvent.getTransactionId(),
                new TransactionActivationRequestedData(
                        transactionActivationRequestedData.getPaymentNotices(),
                        "foo@example.com",
                        "faultCode",
                        "faultCodeString",
                        it.pagopa.ecommerce.commons.documents.Transaction.OriginType.UNKNOWN
                )
        );

        TransactionId transactionId = new TransactionId(transactionUUID);
        PaymentToken paymentToken = new PaymentToken(
                transactionActivationRequestedEvent.getData().getPaymentNotices().get(0).getPaymentToken()
        );
        RptId rptId = new RptId(transactionActivationRequestedEvent.getData().getPaymentNotices().get(0).getRptId());
        TransactionDescription description = new TransactionDescription(
                transactionActivationRequestedData.getPaymentNotices().get(0).getDescription()
        );
        TransactionAmount amount = new TransactionAmount(
                transactionActivationRequestedData.getPaymentNotices().get(0).getAmount()
        );
        Email email = new Email(transactionActivationRequestedData.getEmail());
        ZonedDateTime creationDate = ZonedDateTime.now();
        PaymentContextCode paymentContextCode = new PaymentContextCode(
                transactionActivationRequestedData.getPaymentNotices().get(0).getPaymentContextCode()
        );
        try (
                MockedStatic<ZonedDateTime> zonedDateTime = Mockito.mockStatic(ZonedDateTime.class)) {
            TransactionActivationRequested expected = new TransactionActivationRequested(
                    transactionId,
                    Arrays.asList(
                            new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                    paymentToken,
                                    rptId,
                                    amount,
                                    description,
                                    paymentContextCode
                            )
                    ),
                    email,
                    creationDate,
                    TransactionStatusDto.ACTIVATION_REQUESTED,
                    it.pagopa.ecommerce.commons.documents.Transaction.OriginType.UNKNOWN
            );

            /*
             * Preconditions
             */
            it.pagopa.ecommerce.commons.documents.Transaction transactionDocument = it.pagopa.ecommerce.commons.documents.Transaction
                    .from(expected);
            Mockito.when(
                    viewEventStoreRepository.save(Mockito.any(it.pagopa.ecommerce.commons.documents.Transaction.class))
            )
                    .thenReturn(Mono.just(transactionDocument));
            zonedDateTime.when(ZonedDateTime::now).thenReturn(expected.getCreationDate());

            /*
             * Test
             */
            TransactionActivationRequested result = transactionsProjectionHandler
                    .handle(transactionActivationRequestedEvent).cast(TransactionActivationRequested.class).block();

            /*
             * Assertions
             */
            assertEquals(expected, result);
        }
    }
}
