package it.pagopa.transactions.services.v1;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.PaymentTransferInformation;
import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.documents.v2.activation.EmptyTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.redis.templatewrappers.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.client.WalletClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.configurations.AzureStorageConfig;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.*;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.AutoConfigureDataRedis;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import static it.pagopa.ecommerce.commons.v1.TransactionTestUtils.EMAIL_STRING;
import static org.mockito.ArgumentMatchers.any;

@AutoConfigureDataRedis
class TransactionServiceTest {

    private final TransactionsViewRepository transactionsViewRepository = Mockito
            .mock(TransactionsViewRepository.class);
    @Autowired
    private UUIDUtils uuidUtils;
    private final EcommercePaymentMethodsClient ecommercePaymentMethodsClient = Mockito
            .mock(EcommercePaymentMethodsClient.class);

    private final WalletClient walletClient = Mockito
            .mock(WalletClient.class);

    private final PaymentGatewayClient paymentGatewayClient = Mockito.mock(PaymentGatewayClient.class);

    private final NodeForPspClient nodeForPspClient = Mockito.mock(NodeForPspClient.class);

    private final AzureStorageConfig azureStorageConfig = new AzureStorageConfig();

    private final QueueAsyncClient queueAsyncClientClosureRetryV1 = Mockito.mock(QueueAsyncClient.class);

    private final QueueAsyncClient queueAsyncClientRefundV1 = Mockito.mock(QueueAsyncClient.class);

    private final QueueAsyncClient queueAsyncClientClosureRetryV2 = Mockito.mock(QueueAsyncClient.class);

    private final QueueAsyncClient queueAsyncClientRefundV2 = Mockito.mock(QueueAsyncClient.class);
    private final it.pagopa.transactions.commands.handlers.v1.TransactionActivateHandler transactionActivateHandlerV1 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v1.TransactionActivateHandler.class);
    private final it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler transactionActivateHandlerV2 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler.class);
    private final it.pagopa.transactions.commands.handlers.v1.TransactionUserCancelHandler transactionCancelHandlerV1 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v1.TransactionUserCancelHandler.class);
    private final it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler transactionCancelHandlerV2 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler.class);
    private final it.pagopa.transactions.commands.handlers.v1.TransactionRequestAuthorizationHandler transactionRequestAuthorizationHandlerV1 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v1.TransactionRequestAuthorizationHandler.class);
    private final it.pagopa.transactions.commands.handlers.v2.TransactionRequestAuthorizationHandler transactionRequestAuthorizationHandlerV2 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionRequestAuthorizationHandler.class);
    private final it.pagopa.transactions.commands.handlers.v1.TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandlerV1 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v1.TransactionUpdateAuthorizationHandler.class);
    private final it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandlerV2 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler.class);
    private final it.pagopa.transactions.commands.handlers.v1.TransactionRequestUserReceiptHandler transactionUpdateStatusHandlerV1 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v1.TransactionRequestUserReceiptHandler.class);
    private final it.pagopa.transactions.commands.handlers.v2.TransactionRequestUserReceiptHandler transactionUpdateStatusHandlerV2 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionRequestUserReceiptHandler.class);
    private final it.pagopa.transactions.commands.handlers.v1.TransactionSendClosureHandler transactionSendClosureHandlerV1 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v1.TransactionSendClosureHandler.class);
    private final it.pagopa.transactions.commands.handlers.v2.TransactionSendClosureHandler transactionSendClosureHandlerV2 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionSendClosureHandler.class);

    private final it.pagopa.transactions.projections.handlers.v1.AuthorizationRequestProjectionHandler authorizationProjectionHandlerV1 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v1.AuthorizationRequestProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler authorizationProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v1.AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandlerV1 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v1.AuthorizationUpdateProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v1.TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandlerV1 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v1.TransactionUserReceiptProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.TransactionUserReceiptProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v1.RefundRequestProjectionHandler refundRequestProjectionHandlerV1 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v1.RefundRequestProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.RefundRequestProjectionHandler refundRequestProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.RefundRequestProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v1.ClosureSendProjectionHandler closureSendProjectionHandlerV1 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v1.ClosureSendProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.ClosureSendProjectionHandler closureSendProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.ClosureSendProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v1.ClosureErrorProjectionHandler closureErrorProjectionHandlerV1 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v1.ClosureErrorProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.ClosureErrorProjectionHandler closureErrorProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.ClosureErrorProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v1.TransactionsActivationProjectionHandler transactionsActivationProjectionHandlerV1 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v1.TransactionsActivationProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler transactionsActivationProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v1.CancellationRequestProjectionHandler cancellationRequestProjectionHandlerV1 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v1.CancellationRequestProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler cancellationRequestProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler.class);
    private final TransactionsEventStoreRepository transactionsEventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    @Captor
    private ArgumentCaptor<TransactionRequestAuthorizationCommand> commandArgumentCaptor;

    private final JwtTokenUtils jwtTokenUtils = Mockito.mock(JwtTokenUtils.class);
    private final TransactionsUtils transactionsUtils = Mockito.mock(TransactionsUtils.class);

    private final AuthRequestDataUtils authRequestDataUtils = Mockito.mock(AuthRequestDataUtils.class);

    private final TracingUtils tracingUtils = Mockito.mock(TracingUtils.class);

    private final PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper = Mockito
            .mock(PaymentRequestInfoRedisTemplateWrapper.class);

    private final TransactionsService transactionsServiceV1 = new TransactionsService(
            transactionActivateHandlerV1,
            transactionActivateHandlerV2,
            transactionRequestAuthorizationHandlerV1,
            transactionRequestAuthorizationHandlerV2,
            transactionUpdateAuthorizationHandlerV1,
            transactionUpdateAuthorizationHandlerV2,
            transactionSendClosureHandlerV1,
            transactionSendClosureHandlerV2,
            transactionUpdateStatusHandlerV1,
            transactionUpdateStatusHandlerV2,
            transactionCancelHandlerV1,
            transactionCancelHandlerV2,
            authorizationProjectionHandlerV1,
            authorizationProjectionHandlerV2,
            authorizationUpdateProjectionHandlerV1,
            authorizationUpdateProjectionHandlerV2,
            refundRequestProjectionHandlerV1,
            refundRequestProjectionHandlerV2,
            closureSendProjectionHandlerV1,
            closureSendProjectionHandlerV2,
            closureErrorProjectionHandlerV1,
            closureErrorProjectionHandlerV2,
            cancellationRequestProjectionHandlerV1,
            cancellationRequestProjectionHandlerV2,
            transactionUserReceiptProjectionHandlerV1,
            transactionUserReceiptProjectionHandlerV2,
            transactionsActivationProjectionHandlerV1,
            transactionsActivationProjectionHandlerV2,
            transactionsViewRepository,
            ecommercePaymentMethodsClient,
            walletClient,
            uuidUtils,
            transactionsUtils,
            transactionsEventStoreRepository,
            10,
            EventVersion.V1
    );

    private final TransactionsService transactionsServiceV2 = new TransactionsService(
            transactionActivateHandlerV1,
            transactionActivateHandlerV2,
            transactionRequestAuthorizationHandlerV1,
            transactionRequestAuthorizationHandlerV2,
            transactionUpdateAuthorizationHandlerV1,
            transactionUpdateAuthorizationHandlerV2,
            transactionSendClosureHandlerV1,
            transactionSendClosureHandlerV2,
            transactionUpdateStatusHandlerV1,
            transactionUpdateStatusHandlerV2,
            transactionCancelHandlerV1,
            transactionCancelHandlerV2,
            authorizationProjectionHandlerV1,
            authorizationProjectionHandlerV2,
            authorizationUpdateProjectionHandlerV1,
            authorizationUpdateProjectionHandlerV2,
            refundRequestProjectionHandlerV1,
            refundRequestProjectionHandlerV2,
            closureSendProjectionHandlerV1,
            closureSendProjectionHandlerV2,
            closureErrorProjectionHandlerV1,
            closureErrorProjectionHandlerV2,
            cancellationRequestProjectionHandlerV1,
            cancellationRequestProjectionHandlerV2,
            transactionUserReceiptProjectionHandlerV1,
            transactionUserReceiptProjectionHandlerV2,
            transactionsActivationProjectionHandlerV1,
            transactionsActivationProjectionHandlerV2,
            transactionsViewRepository,
            ecommercePaymentMethodsClient,
            walletClient,
            uuidUtils,
            transactionsUtils,
            transactionsEventStoreRepository,
            10,
            EventVersion.V2
    );

    @Test
    void shouldHandleNewTransactionTransactionActivatedV1Event() {
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
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
        );

        /*
         * Preconditions
         */
        Mockito.when(transactionActivateHandlerV1.handle(any()))
                .thenReturn(Mono.just(response));
        Mockito.when(transactionsActivationProjectionHandlerV1.handle(transactionActivatedEvent))
                .thenReturn(Mono.just(transactionActivated));
        Mockito.when(transactionsUtils.convertEnumerationV1(any()))
                .thenCallRealMethod();
        StepVerifier
                .create(
                        transactionsServiceV1.newTransaction(
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

    @Test
    void shouldHandleNewTransactionTransactionActivatedV2Event() {
        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
        UUID TEST_SESSION_TOKEN = UUID.randomUUID();
        UUID TEST_CPP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(EMAIL_STRING)
                .addPaymentNoticesItem(new PaymentNoticeInfoDto().rptId(TransactionTestUtils.RPT_ID).amount(100));

        it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData transactionActivatedData = new it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedData();
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

        it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent transactionActivatedEvent = new it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent(
                new TransactionId(TRANSACTION_ID).value(),
                transactionActivatedData
        );

        Tuple2<Mono<BaseTransactionEvent<?>>, String> response = Tuples
                .of(
                        Mono.just(transactionActivatedEvent),
                        TEST_SESSION_TOKEN.toString()
                );

        it.pagopa.ecommerce.commons.domain.v2.TransactionActivated transactionActivated = new it.pagopa.ecommerce.commons.domain.v2.TransactionActivated(
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
                it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT,
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
        Mockito.when(transactionsUtils.convertEnumerationV1(any()))
                .thenCallRealMethod();
        StepVerifier
                .create(
                        transactionsServiceV2.newTransaction(
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
