package it.pagopa.transactions.projections.handlers;

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
        transactionActivationRequestedData.setPaymentContextCode("ccpcode");
        transactionActivationRequestedData.setEmail("email@test.it");
        transactionActivationRequestedData.setDescription("reason");
        transactionActivationRequestedData.setAmount(1);
        transactionActivationRequestedData.setFaultCode("faultCode");
        transactionActivationRequestedData.setFaultCodeString("faultCodeString");

        TransactionActivationRequestedEvent transactionActivationRequestedEvent = new TransactionActivationRequestedEvent(transactionUUID.toString(), "77777777777302016723749670035", transactionActivationRequestedData);

        TransactionActivationRequestedEvent event = new TransactionActivationRequestedEvent(
                transactionActivationRequestedEvent.getTransactionId(),
                transactionActivationRequestedEvent.getRptId(),
                transactionActivationRequestedEvent.getPaymentToken(),
                new TransactionActivationRequestedData(
                        transactionActivationRequestedData.getDescription(),
                        transactionActivationRequestedData.getAmount(),
                        "foo@example.com",
                        "faultCode",
                        "faultCodeString",
                        "paymentContextCode"
                )
        );

        TransactionId transactionId = new TransactionId(transactionUUID);
        PaymentToken paymentToken = new PaymentToken(transactionActivationRequestedEvent.getPaymentToken());
        RptId rptId = new RptId(transactionActivationRequestedEvent.getRptId());
        TransactionDescription description = new TransactionDescription(transactionActivationRequestedData.getDescription());
        TransactionAmount amount = new TransactionAmount(transactionActivationRequestedData.getAmount());
        Email email = new Email(transactionActivationRequestedData.getEmail());
        ZonedDateTime creationDate = ZonedDateTime.now();

        try (
                MockedStatic<ZonedDateTime> zonedDateTime = Mockito.mockStatic(ZonedDateTime.class)) {
            TransactionActivationRequested expected = new TransactionActivationRequested(
                    transactionId,
                    rptId,
                    description,
                    amount,
                    email,
                    creationDate,
                    TransactionStatusDto.ACTIVATION_REQUESTED);

            /*
             * Preconditions
             */
            it.pagopa.ecommerce.commons.documents.Transaction transactionDocument = it.pagopa.ecommerce.commons.documents.Transaction
                    .from(expected);
            Mockito.when(viewEventStoreRepository.save(Mockito.any(it.pagopa.ecommerce.commons.documents.Transaction.class)))
                    .thenReturn(Mono.just(transactionDocument));
            zonedDateTime.when(ZonedDateTime::now).thenReturn(expected.getCreationDate());

            /*
             * Test
             */
            TransactionActivationRequested result = transactionsProjectionHandler.handle(transactionActivationRequestedEvent).cast(TransactionActivationRequested.class).block();

            /*
             * Assertions
             */
            assertEquals(expected, result);
        }
    }
}
