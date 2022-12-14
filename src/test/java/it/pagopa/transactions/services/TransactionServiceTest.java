package it.pagopa.transactions.services;

import it.pagopa.ecommerce.commons.documents.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.documents.TransactionActivationRequestedData;
import it.pagopa.ecommerce.commons.documents.TransactionActivationRequestedEvent;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.NewTransactionResponseDto;
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
    void shouldHandleNewTransaction() {
        String TEST_EMAIL = "j.doe@mail.com";
        String TEST_RPTID = "77777777777302016723749670035";
        String TEST_TOKEN = "token";
        UUID TEST_SESSION_TOKEN = UUID.randomUUID();
        UUID TEST_CPP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(TEST_EMAIL)
                .rptId(TEST_RPTID);

        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setDescription("dest");
        transactionActivatedData.setAmount(0);
        transactionActivatedData.setEmail(TEST_EMAIL);
        transactionActivatedData.setPaymentToken(TEST_TOKEN);

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(TRANSACTION_ID.toString(), TEST_RPTID, TEST_TOKEN, transactionActivatedData);

        TransactionActivationRequestedData transactionActivationRequestedData = new TransactionActivationRequestedData();
        transactionActivationRequestedData.setAmount(0);
        transactionActivationRequestedData.setDescription("desc");
        transactionActivationRequestedData.setEmail(TEST_EMAIL);
        transactionActivationRequestedData.setPaymentContextCode(TEST_CPP.toString());

        TransactionActivationRequestedEvent transactionActivationRequestedEvent = new TransactionActivationRequestedEvent(TRANSACTION_ID.toString(), TEST_RPTID, transactionActivationRequestedData);

        SessionDataDto sessionDataDto = new SessionDataDto();
        sessionDataDto.setEmail(TEST_EMAIL);
        sessionDataDto.sessionToken(TEST_SESSION_TOKEN.toString());
        sessionDataDto.setTransactionId(TRANSACTION_ID.toString());
        sessionDataDto.setPaymentToken(TEST_TOKEN);
        sessionDataDto.setRptId(TEST_RPTID);

        Tuple3<
                Mono<TransactionActivatedEvent>,
                Mono<TransactionActivationRequestedEvent>,
                SessionDataDto> response = Tuples.of(Mono.just(transactionActivatedEvent), Mono.just(transactionActivationRequestedEvent), sessionDataDto);

        TransactionActivated transactionActivated = new TransactionActivated(
                new TransactionId(TRANSACTION_ID),
                new PaymentToken(TEST_TOKEN),
                new RptId(TEST_RPTID),
                new TransactionDescription("desc"),
                new TransactionAmount(0),
                new Email("foo@example.com"),
                "faultCode",
                "faultCodeString",
                TransactionStatusDto.ACTIVATED
        );


        TransactionActivationRequested transactionActivationRequested = new TransactionActivationRequested(
                new TransactionId(TRANSACTION_ID),
                new RptId(TEST_RPTID),
                new TransactionDescription("desc"),
                new TransactionAmount(0),
                new Email("foo@example.com"),
                TransactionStatusDto.ACTIVATION_REQUESTED
        );

        /**
         * Preconditions
         */
        Mockito.when(transactionActivateHandler.handle(Mockito.any(TransactionActivateCommand.class))).thenReturn(Mono.just(response));
//        Mockito.when(transactionsProjectionHandler.handle(transactionActivationRequestedEvent)).thenReturn(Mono.just(transactionActivationRequested));
        Mockito.when(transactionsActivationProjectionHandler.handle(transactionActivatedEvent)).thenReturn(Mono.just(transactionActivated));

        /**
         * Test
         */
        NewTransactionResponseDto responseDto = transactionsService.newTransaction(transactionRequestDto).block();

        /**
         * Assertions
         */
        assertEquals(transactionRequestDto.getRptId(), responseDto.getRptId());
    }
}
