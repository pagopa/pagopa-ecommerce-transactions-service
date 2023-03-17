package it.pagopa.transactions.services;

import it.pagopa.ecommerce.commons.documents.v1.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.Confidential;
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
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private ConfidentialDataManager confidentialDataManager = TransactionTestUtils.confidentialDataManager;

    @Test
    void shouldHandleNewTransactionTransactionActivated() throws Exception {
        String TEST_EMAIL_STRING = "j.doe@mail.com";
        Confidential<Email> TEST_EMAIL = confidentialDataManager
                .encrypt(ConfidentialDataManager.Mode.AES_GCM_NOPAD, new Email(TEST_EMAIL_STRING));
        String TEST_RPTID = "77777777777302016723749670035";
        String TEST_TOKEN = "token";
        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
        UUID TEST_SESSION_TOKEN = UUID.randomUUID();
        UUID TEST_CPP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(TEST_EMAIL_STRING)
                .addPaymentNoticesItem(new PaymentNoticeInfoDto().rptId(TEST_RPTID));

        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail(TEST_EMAIL);
        transactionActivatedData
                .setPaymentNotices(List.of(new PaymentNotice(TEST_TOKEN, null, "dest", 0, TEST_CPP.toString())));

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                TRANSACTION_ID.toString(),
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
                                new PaymentToken(TEST_TOKEN),
                                new RptId(TEST_RPTID),
                                new TransactionAmount(0),
                                new TransactionDescription("desc"),
                                new PaymentContextCode(TEST_CPP.toString())
                        )
                ),
                TEST_EMAIL,
                "faultCode",
                "faultCodeString",
                Transaction.ClientId.CHECKOUT
        );

        /**
         * Preconditions
         */
        Mockito.when(transactionActivateHandler.handle(Mockito.any(TransactionActivateCommand.class)))
                .thenReturn(Mono.just(response));
        Mockito.when(transactionsActivationProjectionHandler.handle(transactionActivatedEvent))
                .thenReturn(Mono.just(transactionActivated));
        Mockito.when(transactionsUtils.convertEnumeration(any()))
                .thenAnswer(args -> TransactionStatusDto.fromValue(args.getArgument(0).toString()));
        /**
         * Test
         */
        NewTransactionResponseDto responseDto = transactionsService
                .newTransaction(transactionRequestDto, clientIdDto).block();

        /**
         * Assertions
         */
        assertEquals(
                transactionRequestDto.getPaymentNotices().get(0).getRptId(),
                responseDto.getPayments().get(0).getRptId()
        );
    }

}
