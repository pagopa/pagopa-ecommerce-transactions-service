package it.pagopa.transactions.services;

import it.pagopa.ecommerce.commons.documents.v1.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.domain.v1.*;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.commands.handlers.TransactionActivateHandler;
import it.pagopa.transactions.projections.handlers.TransactionsActivationProjectionHandler;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static it.pagopa.ecommerce.commons.v1.TransactionTestUtils.EMAIL_STRING;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @InjectMocks
    private TransactionsService transactionsService;

    @Mock
    private TransactionActivateHandler transactionActivateHandler;

    @Mock
    private TransactionsActivationProjectionHandler transactionsActivationProjectionHandler;

    @Mock
    private TransactionsUtils transactionsUtils;

    @Mock
    private ConfidentialDataManager confidentialDataManager;

    @Test
    void shouldHandleNewTransactionTransactionActivated() {
        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
        UUID TEST_SESSION_TOKEN = UUID.randomUUID();
        UUID TEST_CPP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(EMAIL_STRING)
                .addPaymentNoticesItem(new PaymentNoticeInfoDto().rptId(TransactionTestUtils.RPT_ID));

        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail(TransactionTestUtils.EMAIL);
        transactionActivatedData
                .setPaymentNotices(
                        List.of(
                                new PaymentNotice(
                                        TransactionTestUtils.PAYMENT_TOKEN,
                                        null,
                                        "dest",
                                        0,
                                        TEST_CPP.toString(),
                                        List.of(new PaymentTransferInformation("77777777777", false, 0, null))
                                )
                        )
                );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                new TransactionId(TRANSACTION_ID).value(),
                transactionActivatedData
        );

        Tuple2<Mono<TransactionActivatedEvent>, String> response = Tuples
                .of(
                        Mono.just(transactionActivatedEvent),
                        TEST_SESSION_TOKEN.toString()
                );

        TransactionActivated transactionActivated = new TransactionActivated(
                new TransactionId(TRANSACTION_ID),
                Arrays.asList(
                        new it.pagopa.ecommerce.commons.domain.v1.PaymentNotice(
                                new PaymentToken(TransactionTestUtils.PAYMENT_TOKEN),
                                new RptId(TransactionTestUtils.RPT_ID),
                                new TransactionAmount(0),
                                new TransactionDescription("desc"),
                                new PaymentContextCode(TEST_CPP.toString()),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null))
                        )
                ),
                TransactionTestUtils.EMAIL,
                "faultCode",
                "faultCodeString",
                Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
        );

        /*
         * Preconditions
         */
        Mockito.when(transactionActivateHandler.handle(Mockito.any(TransactionActivateCommand.class)))
                .thenReturn(Mono.just(response));
        Mockito.when(transactionsActivationProjectionHandler.handle(transactionActivatedEvent))
                .thenReturn(Mono.just(transactionActivated));
        Mockito.when(transactionsUtils.convertEnumeration(any()))
                .thenCallRealMethod();

        StepVerifier.create(transactionsService.newTransaction(transactionRequestDto, clientIdDto))
                .expectNextMatches(
                        res -> res.getPayments().get(0).getRptId()
                                .equals(transactionRequestDto.getPaymentNotices().get(0).getRptId())
                                && res.getIdCart().equals("idCart")
                                && res.getStatus().equals(TransactionStatusDto.ACTIVATED)
                                && res.getClientId()
                                        .equals(NewTransactionResponseDto.ClientIdEnum.valueOf(clientIdDto.getValue()))
                                && !res.getTransactionId().isEmpty()
                                && !res.getAuthToken().isEmpty()
                )
                .verifyComplete();

    }

}
