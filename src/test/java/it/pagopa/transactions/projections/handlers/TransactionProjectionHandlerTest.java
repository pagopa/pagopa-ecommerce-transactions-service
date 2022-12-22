package it.pagopa.transactions.projections.handlers;

import it.pagopa.ecommerce.commons.documents.NoticeCode;
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
        NoticeCode noticeCode = new NoticeCode(null, null, "reason", 1, null);
        transactionActivationRequestedData.setNoticeCodes(Arrays.asList(noticeCode));
        transactionActivationRequestedData.setFaultCode("faultCode");
        transactionActivationRequestedData.setFaultCodeString("faultCodeString");

        TransactionActivationRequestedEvent transactionActivationRequestedEvent = new TransactionActivationRequestedEvent(
                transactionUUID.toString(),
                Arrays.asList(
                        new it.pagopa.ecommerce.commons.documents.NoticeCode(
                                null,
                                "77777777777302016723749670035",
                                "reason",
                                1,
                                "ccpcode"
                        )
                ),
                transactionActivationRequestedData
        );

        TransactionActivationRequestedEvent event = new TransactionActivationRequestedEvent(
                transactionActivationRequestedEvent.getTransactionId(),
                transactionActivationRequestedEvent.getNoticeCodes(),
                new TransactionActivationRequestedData(
                        transactionActivationRequestedData.getNoticeCodes(),
                        "foo@example.com",
                        "faultCode",
                        "faultCodeString"
                )
        );

        TransactionId transactionId = new TransactionId(transactionUUID);
        PaymentToken paymentToken = new PaymentToken(
                transactionActivationRequestedEvent.getNoticeCodes().get(0).getPaymentToken()
        );
        RptId rptId = new RptId(transactionActivationRequestedEvent.getNoticeCodes().get(0).getRptId());
        TransactionDescription description = new TransactionDescription(
                transactionActivationRequestedData.getNoticeCodes().get(0).getDescription()
        );
        TransactionAmount amount = new TransactionAmount(
                transactionActivationRequestedData.getNoticeCodes().get(0).getAmount()
        );
        Email email = new Email(transactionActivationRequestedData.getEmail());
        ZonedDateTime creationDate = ZonedDateTime.now();
        PaymentContextCode paymentContextCode = new PaymentContextCode(
                transactionActivationRequestedData.getNoticeCodes().get(0).getPaymentContextCode()
        );
        try (
                MockedStatic<ZonedDateTime> zonedDateTime = Mockito.mockStatic(ZonedDateTime.class)) {
            TransactionActivationRequested expected = new TransactionActivationRequested(
                    transactionId,
                    Arrays.asList(
                            new it.pagopa.ecommerce.commons.domain.NoticeCode(
                                    paymentToken,
                                    rptId,
                                    amount,
                                    description,
                                    paymentContextCode
                            )
                    ),
                    email,
                    creationDate,
                    TransactionStatusDto.ACTIVATION_REQUESTED
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
