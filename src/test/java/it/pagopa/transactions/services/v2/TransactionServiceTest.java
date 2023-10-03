package it.pagopa.transactions.services.v2;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.PaymentTransferInformation;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.documents.v2.activation.EmptyTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.v2.TransactionActivated;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.generated.transactions.v2.server.model.*;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.*;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.AutoConfigureDataRedis;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static it.pagopa.ecommerce.commons.v2.TransactionTestUtils.EMAIL_STRING;
import static org.mockito.ArgumentMatchers.any;

@AutoConfigureDataRedis
class TransactionServiceTest {
    private final TransactionsViewRepository transactionsViewRepository = Mockito
            .mock(TransactionsViewRepository.class);
    private final it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler transactionActivateHandlerV2 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler transactionsActivationProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler.class);

    private final TransactionsUtils transactionsUtils = Mockito.mock(TransactionsUtils.class);

    @Autowired
    private final TransactionsService transactionsService = new TransactionsService(
            transactionActivateHandlerV2,
            transactionsActivationProjectionHandlerV2,
            transactionsUtils,
            EventVersion.V2
    );

    @Test
    void shouldHandleNewTransactionTransactionActivated() {
        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
        UUID TEST_SESSION_TOKEN = UUID.randomUUID();
        UUID TEST_CPP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(EMAIL_STRING)
                .addPaymentNoticesItem(new PaymentNoticeInfoDto().rptId(TransactionTestUtils.RPT_ID).amount(100));

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
                                        List.of(new PaymentTransferInformation("77777777777", false, 0, null)),
                                        false
                                )
                        )
                );

        TransactionActivatedEvent transactionActivatedEvent = new TransactionActivatedEvent(
                new TransactionId(TRANSACTION_ID).value(),
                transactionActivatedData
        );

        Tuple2<Mono<BaseTransactionEvent<?>>, String> response = Tuples
                .of(
                        Mono.just(transactionActivatedEvent),
                        TEST_SESSION_TOKEN.toString()
                );

        TransactionActivated transactionActivated = new TransactionActivated(
                new TransactionId(TRANSACTION_ID),
                Arrays.asList(
                        new it.pagopa.ecommerce.commons.domain.PaymentNotice(
                                new PaymentToken(TransactionTestUtils.PAYMENT_TOKEN),
                                new RptId(TransactionTestUtils.RPT_ID),
                                new TransactionAmount(0),
                                new TransactionDescription("desc"),
                                new PaymentContextCode(TEST_CPP.toString()),
                                List.of(new PaymentTransferInfo("77777777777", false, 100, null)),
                                false
                        )
                ),
                TransactionTestUtils.EMAIL,
                "faultCode",
                "faultCodeString",
                Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new EmptyTransactionGatewayActivationData()
        );

        /*
         * Preconditions
         */
        Mockito.when(transactionActivateHandlerV2.handle(any()))
                .thenReturn(Mono.just(response));
        Mockito.when(transactionsActivationProjectionHandlerV2.handle(transactionActivatedEvent))
                .thenReturn(Mono.just(transactionActivated));
        Mockito.when(transactionsUtils.convertEnumerationV2(any()))
                .thenCallRealMethod();
        Hooks.onOperatorDebug();
        StepVerifier
                .create(
                        transactionsService.newTransaction(
                                transactionRequestDto,
                                clientIdDto,
                                new TransactionId(transactionActivatedEvent.getTransactionId())
                        )
                )
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
