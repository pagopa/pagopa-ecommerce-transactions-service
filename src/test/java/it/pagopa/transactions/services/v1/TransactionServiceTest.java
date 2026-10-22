package it.pagopa.transactions.services.v1;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestData;
import it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.NpgTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.documents.v2.authorization.RedirectTransactionGatewayAuthorizationRequestedData;
import it.pagopa.ecommerce.commons.domain.v2.*;
import it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.redis.reactivetemplatewrappers.v2.ReactivePaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManagerTest;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.*;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.boot.test.autoconfigure.data.redis.AutoConfigureDataRedis;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Stream;

import static it.pagopa.ecommerce.commons.v2.TransactionTestUtils.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@AutoConfigureDataRedis
class TransactionServiceTest {
    private final TransactionsViewRepository transactionsViewRepository = Mockito
            .mock(TransactionsViewRepository.class);
    private final EcommercePaymentMethodsClient ecommercePaymentMethodsClient = Mockito
            .mock(EcommercePaymentMethodsClient.class);
    private final EcommercePaymentMethodsHandlerClient ecommercePaymentMethodsHandlerClient = Mockito
            .mock(EcommercePaymentMethodsHandlerClient.class);

    private final WalletClient walletClient = Mockito
            .mock(WalletClient.class);

    private final it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler transactionCancelHandlerV2 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler.class);
    private final it.pagopa.transactions.commands.handlers.v2.TransactionRequestAuthorizationHandler transactionRequestAuthorizationHandlerV2 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionRequestAuthorizationHandler.class);
    private final it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandlerV2 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler.class);
    private final it.pagopa.transactions.commands.handlers.v2.TransactionRequestUserReceiptHandler transactionUpdateStatusHandlerV2 = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionRequestUserReceiptHandler.class);
    private final it.pagopa.transactions.commands.handlers.v2.TransactionSendClosureRequestHandler transactionSendClosureRequestHandler = Mockito
            .mock(it.pagopa.transactions.commands.handlers.v2.TransactionSendClosureRequestHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler authorizationProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.TransactionUserReceiptProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.ClosureRequestedProjectionHandler closureRequestedProjectionHandler = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.ClosureRequestedProjectionHandler.class);
    private final it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler cancellationRequestProjectionHandlerV2 = Mockito
            .mock(it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler.class);
    private final TransactionsEventStoreRepository transactionsEventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private final TransactionsUtils transactionsUtils = Mockito.mock(TransactionsUtils.class);

    private final ReactivePaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper = Mockito
            .mock(ReactivePaymentRequestInfoRedisTemplateWrapper.class);

    private final UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils = Mockito
            .mock(UpdateTransactionStatusTracerUtils.class);

    private final Map<String, String> npgAuthorizationErrorCodeMapping = new HashMap<>();

    private final Set<String> ecommerceFinalStates = Set.of(
            "NOTIFIED_OK",
            "NOTIFIED_KO",
            "NOTIFICATION_ERROR",
            "NOTIFICATION_REQUESTED",
            "EXPIRED",
            "REFUNDED",
            "CANCELED",
            "UNAUTHORIZED",
            "REFUND_ERROR",
            "REFUND_REQUESTED",
            "CANCELLATION_EXPIRED"
    );

    private final Set<String> ecommercePossibleFinalStates = Set
            .of("AUTHORIZATION_COMPLETED", "CLOSURE_REQUESTED", "CLOSURE_ERROR");

    private final boolean enablePaymentMethodsHandler = false;

    private final OpenTelemetryUtils openTelemetryUtils = mock(OpenTelemetryUtils.class);

    private final TransactionsService transactionsServiceV1 = new TransactionsService(
            transactionRequestAuthorizationHandlerV2,
            transactionUpdateAuthorizationHandlerV2,
            transactionSendClosureRequestHandler,
            transactionUpdateStatusHandlerV2,
            transactionCancelHandlerV2,
            authorizationProjectionHandlerV2,
            authorizationUpdateProjectionHandlerV2,
            closureRequestedProjectionHandler,
            cancellationRequestProjectionHandlerV2,
            transactionUserReceiptProjectionHandlerV2,
            transactionsViewRepository,
            ecommercePaymentMethodsClient,
            ecommercePaymentMethodsHandlerClient,
            walletClient,
            transactionsUtils,
            transactionsEventStoreRepository,
            paymentRequestInfoRedisTemplateWrapper,
            updateTransactionStatusTracerUtils,
            npgAuthorizationErrorCodeMapping,
            ecommerceFinalStates,
            ecommercePossibleFinalStates,
            enablePaymentMethodsHandler,
            openTelemetryUtils
    );

    private static Stream<Arguments> authRequestMethodSource() {
        return Stream.of(
                Arguments.of(
                        it.pagopa.ecommerce.commons.v2.TransactionTestUtils.transactionAuthorizationRequestedEvent(
                                TransactionAuthorizationRequestData.PaymentGateway.NPG,
                                new NpgTransactionGatewayAuthorizationRequestedData()
                        ),
                        new UpdateAuthorizationRequestDto()
                                .outcomeGateway(
                                        new OutcomeNpgGatewayDto()
                                                .authorizationCode("authorizationCode")
                                                .operationResult(OutcomeNpgGatewayDto.OperationResultEnum.EXECUTED)
                                ).timestampOperation(OffsetDateTime.now()),
                        UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.NPG,
                        "EXECUTED"
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.v2.TransactionTestUtils.transactionAuthorizationRequestedEvent(
                                TransactionAuthorizationRequestData.PaymentGateway.REDIRECT,
                                new RedirectTransactionGatewayAuthorizationRequestedData(
                                        LOGO_URI,
                                        REDIRECT_AUTHORIZATION_TIMEOUT
                                )
                        ),
                        new UpdateAuthorizationRequestDto()
                                .outcomeGateway(
                                        new OutcomeRedirectGatewayDto()
                                                .authorizationCode("authorizationCode")
                                                .outcome(AuthorizationOutcomeDto.OK)
                                                .pspId(it.pagopa.ecommerce.commons.v2.TransactionTestUtils.PSP_ID)
                                                .pspTransactionId(
                                                        it.pagopa.ecommerce.commons.v2.TransactionTestUtils.AUTHORIZATION_REQUEST_ID
                                                )
                                ).timestampOperation(OffsetDateTime.now()),
                        UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger.REDIRECT,
                        "OK"
                )
        );
    }

    @ParameterizedTest
    @MethodSource("authRequestMethodSource")
    void shouldTraceTransactionUpdateStatusOK(
                                              TransactionAuthorizationRequestedEvent transactionAuthorizationRequestedEvent,
                                              UpdateAuthorizationRequestDto updateAuthorizationRequest,
                                              UpdateTransactionStatusTracerUtils.UpdateTransactionTrigger trigger,
                                              String expectedOutcome
    ) {
        Hooks.onOperatorDebug();
        TransactionId transactionId = new TransactionId(UUID.randomUUID());
        String expectedPaymentMethodTypeCode = transactionAuthorizationRequestedEvent.getData().getPaymentTypeCode();
        String expectedPspId = transactionAuthorizationRequestedEvent.getData().getPspId();

        it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent transactionActivatedEvent = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivateEvent();

        it.pagopa.ecommerce.commons.domain.v2.TransactionActivated transactionActivated = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivated(transactionActivatedEvent.getCreationDate());
        Flux<BaseTransactionEvent<Object>> events = ((Flux) Flux
                .just(transactionActivatedEvent, transactionAuthorizationRequestedEvent));

        it.pagopa.ecommerce.commons.documents.v2.Transaction closureRequestedTransaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        ZonedDateTime.now()
                );

        TransactionInfoDto expected = new TransactionInfoDto()
                .payments(
                        closureRequestedTransaction.getPaymentNotices().stream().map(
                                paymentNotice -> new PaymentInfoDto()
                                        .amount(paymentNotice.getAmount())
                                        .reason(paymentNotice.getDescription())
                                        .paymentToken(paymentNotice.getPaymentToken())
                                        .rptId(paymentNotice.getRptId())
                        )
                                .toList()
                )
                .transactionId(closureRequestedTransaction.getTransactionId())
                .status(TransactionStatusDto.CLOSURE_REQUESTED);

        /* preconditions */
        Mockito.when(transactionsEventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value()))
                .thenReturn(events);
        Mockito.when(
                transactionsUtils.reduceEvents(
                        any(),
                        any(),
                        any(),
                        any()
                )
        ).thenCallRealMethod();

        Mockito.when(transactionsUtils.convertEnumerationV1(any())).thenCallRealMethod();
        Mockito.when(transactionsUtils.getPspId(any(BaseTransaction.class))).thenCallRealMethod();
        Mockito.when(transactionsUtils.getPaymentMethodTypeCode(any(BaseTransaction.class))).thenCallRealMethod();

        Mockito.when(transactionsEventStoreRepository.findByTransactionIdAndEventCode(any(), any()))
                .thenReturn(Mono.empty());

        Mockito.when(transactionUpdateAuthorizationHandlerV2.handle(any())).thenReturn(
                Mono.just(transactionAuthorizationCompletedEvent(new NpgTransactionGatewayAuthorizationData()))
        );

        Mockito.when(authorizationUpdateProjectionHandlerV2.handle(any())).thenReturn(Mono.just(transactionActivated));

        Mockito.when(transactionSendClosureRequestHandler.handle(any()))
                .thenReturn(Mono.just(transactionClosureRequestedEvent()));
        Mockito.when(closureRequestedProjectionHandler.handle(any()))
                .thenReturn(Mono.just(closureRequestedTransaction));

        Mockito.when(transactionsUtils.getPspId(any(BaseTransaction.class))).thenCallRealMethod();
        Mockito.when(transactionsUtils.getPaymentMethodTypeCode(any(BaseTransaction.class))).thenCallRealMethod();
        Mockito.when(transactionsUtils.isWalletPayment(any(BaseTransaction.class))).thenCallRealMethod();
        Mockito.when(transactionsUtils.reduceV2Events(any())).thenCallRealMethod();
        /* test */
        StepVerifier.create(
                transactionsServiceV1
                        .updateTransactionAuthorization(
                                transactionId.uuid(),
                                updateAuthorizationRequest
                        )
        )
                .assertNext(actual -> assertEquals(expected, actual))
                .verifyComplete();

        UpdateTransactionStatusTracerUtils.StatusUpdateInfo expectedStatusUpdateInfo = new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdate(
                trigger,
                UpdateTransactionStatusTracerUtils.UpdateTransactionStatusOutcome.OK,
                new UpdateTransactionStatusTracerUtils.PaymentGatewayStatusUpdateContext(
                        expectedPspId,
                        new UpdateTransactionStatusTracerUtils.GatewayOutcomeResult(
                                expectedOutcome,
                                Optional.empty()
                        ),
                        expectedPaymentMethodTypeCode,
                        transactionActivated.getClientId(),
                        false
                )
        );
        verify(updateTransactionStatusTracerUtils, times(1)).traceStatusUpdateOperation(
                expectedStatusUpdateInfo
        );
    }
}
