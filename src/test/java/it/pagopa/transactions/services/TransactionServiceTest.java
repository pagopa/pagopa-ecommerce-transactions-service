package it.pagopa.transactions.services;

import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.Transaction;
import it.pagopa.ecommerce.commons.documents.*;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.generated.transactions.server.model.ClientIdDto;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.generated.transactions.server.model.PaymentNoticeInfoDto;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.commands.handlers.TransactionActivateHandler;
import it.pagopa.transactions.projections.handlers.TransactionsActivationProjectionHandler;
import it.pagopa.transactions.projections.handlers.TransactionsActivationRequestedProjectionHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple3;
import reactor.util.function.Tuples;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @InjectMocks
    private TransactionsService transactionsService;

    @Mock
    private TransactionActivateHandler transactionActivateHandler;

    @Mock
    private TransactionsActivationRequestedProjectionHandler transactionsProjectionHandler;

    @Mock
    private TransactionsActivationProjectionHandler transactionsActivationProjectionHandler;

    @Test
    void shouldHandleNewTransactionTransactionActivated() {
        String TEST_EMAIL = "j.doe@mail.com";
        String TEST_RPTID = "77777777777302016723749670035";
        String TEST_TOKEN = "token";
        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
        UUID TEST_SESSION_TOKEN = UUID.randomUUID();
        UUID TEST_CPP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(TEST_EMAIL)
                .addPaymentNoticesItem(new PaymentNoticeInfoDto().rptId(TEST_RPTID));

        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail(TEST_EMAIL);
        transactionActivatedData
                .setPaymentNotices(Arrays.asList(new PaymentNotice(TEST_TOKEN, null, "dest", 0, TEST_CPP.toString())));

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                TRANSACTION_ID.toString(),
                transactionActivatedData
        );

        TransactionActivationRequestedData transactionActivationRequestedData = new TransactionActivationRequestedData();
        transactionActivatedData
                .setPaymentNotices(Arrays.asList(new PaymentNotice(TEST_TOKEN, null, "dest", 0, TEST_CPP.toString())));
        transactionActivationRequestedData.setEmail(TEST_EMAIL);

        TransactionActivationRequestedEvent transactionActivationRequestedEvent = new TransactionActivationRequestedEvent(
                TRANSACTION_ID.toString(),
                transactionActivationRequestedData
        );

        SessionDataDto sessionDataDto = new SessionDataDto();
        sessionDataDto.setEmail(TEST_EMAIL);
        sessionDataDto.sessionToken(TEST_SESSION_TOKEN.toString());
        sessionDataDto.setTransactionId(TRANSACTION_ID.toString());
        sessionDataDto.setPaymentToken(TEST_TOKEN);
        sessionDataDto.setRptId(TEST_RPTID);

        Tuple3<Mono<TransactionActivatedEvent>, Mono<TransactionActivationRequestedEvent>, SessionDataDto> response = Tuples
                .of(
                        Mono.just(transactionActivatedEvent),
                        Mono.just(transactionActivationRequestedEvent),
                        sessionDataDto
                );

        TransactionActivated transactionActivated = new TransactionActivated(
                new TransactionId(TRANSACTION_ID),
                Arrays.asList(
                        new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                new PaymentToken(TEST_TOKEN),
                                new RptId(TEST_RPTID),
                                new TransactionAmount(0),
                                new TransactionDescription("desc"),
                                new PaymentContextCode(TEST_CPP.toString())
                        )
                ),
                new Email("foo@example.com"),
                "faultCode",
                "faultCodeString",
                TransactionStatusDto.ACTIVATED,
                Transaction.OriginType.UNKNOWN
        );

        TransactionActivationRequested transactionActivationRequested = new TransactionActivationRequested(
                new TransactionId(TRANSACTION_ID),
                Arrays.asList(
                        new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                null,
                                new RptId(TEST_RPTID),
                                new TransactionAmount(0),
                                new TransactionDescription("desc"),
                                new PaymentContextCode(TEST_CPP.toString())
                        )
                ),
                new Email("foo@example.com"),
                TransactionStatusDto.ACTIVATION_REQUESTED,
                Transaction.OriginType.UNKNOWN
        );

        /**
         * Preconditions
         */
        Mockito.when(transactionActivateHandler.handle(Mockito.any(TransactionActivateCommand.class)))
                .thenReturn(Mono.just(response));
//        Mockito.when(transactionsProjectionHandler.handle(transactionActivationRequestedEvent)).thenReturn(Mono.just(transactionActivationRequested));
        Mockito.when(transactionsActivationProjectionHandler.handle(transactionActivatedEvent))
                .thenReturn(Mono.just(transactionActivated));

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

    @Test
    void shouldHandleNewTransactionTransactionActivationRequested() {
        String TEST_EMAIL = "j.doe@mail.com";
        String TEST_RPTID = "77777777777302016723749670035";
        String TEST_TOKEN = "token";
        String paymentToken = "paymentToken";
        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
        UUID TEST_SESSION_TOKEN = UUID.randomUUID();
        UUID TEST_CPP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(TEST_EMAIL)
                .addPaymentNoticesItem(new PaymentNoticeInfoDto().rptId(TEST_RPTID));

        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail(TEST_EMAIL);
        transactionActivatedData
                .setPaymentNotices(Arrays.asList(new PaymentNotice(TEST_TOKEN, null, "dest", 0, TEST_CPP.toString())));

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                TRANSACTION_ID.toString(),
                transactionActivatedData
        );

        TransactionActivationRequestedData transactionActivationRequestedData = new TransactionActivationRequestedData();
        transactionActivatedData
                .setPaymentNotices(Arrays.asList(new PaymentNotice(TEST_TOKEN, null, "dest", 0, TEST_CPP.toString())));
        transactionActivationRequestedData.setEmail(TEST_EMAIL);

        TransactionActivationRequestedEvent transactionActivationRequestedEvent = new TransactionActivationRequestedEvent(
                TRANSACTION_ID.toString(),
                transactionActivationRequestedData
        );

        SessionDataDto sessionDataDto = new SessionDataDto();
        sessionDataDto.setEmail(TEST_EMAIL);
        sessionDataDto.sessionToken(TEST_SESSION_TOKEN.toString());
        sessionDataDto.setTransactionId(TRANSACTION_ID.toString());
        sessionDataDto.setPaymentToken(TEST_TOKEN);
        sessionDataDto.setRptId(TEST_RPTID);

        Tuple3<Mono<TransactionActivatedEvent>, Mono<TransactionActivationRequestedEvent>, SessionDataDto> response = Tuples
                .of(
                        Mono.empty(),
                        Mono.just(transactionActivationRequestedEvent),
                        sessionDataDto
                );
        List<it.pagopa.ecommerce.commons.domain.PaymentNotice> PaymentNoticeList = List.of(
                new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                        new PaymentToken(TEST_TOKEN),
                        new RptId(TEST_RPTID),
                        new TransactionAmount(0),
                        new TransactionDescription("desc"),
                        new PaymentContextCode(TEST_CPP.toString())
                )
        );
        TransactionActivated transactionActivated = new TransactionActivated(
                new TransactionId(TRANSACTION_ID),
                PaymentNoticeList,
                new Email("foo@example.com"),
                "faultCode",
                "faultCodeString",
                TransactionStatusDto.ACTIVATED,
                Transaction.OriginType.UNKNOWN
        );

        TransactionActivationRequested transactionActivationRequested = new TransactionActivationRequested(
                new TransactionId(TRANSACTION_ID),
                PaymentNoticeList,
                new Email("foo@example.com"),
                TransactionStatusDto.ACTIVATION_REQUESTED,
                Transaction.OriginType.UNKNOWN
        );

        /**
         * Preconditions
         */
        Mockito.when(transactionActivateHandler.handle(Mockito.any(TransactionActivateCommand.class)))
                .thenReturn(Mono.just(response));
        Mockito.when(transactionsProjectionHandler.handle(transactionActivationRequestedEvent))
                .thenReturn(Mono.just(transactionActivationRequested));

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
