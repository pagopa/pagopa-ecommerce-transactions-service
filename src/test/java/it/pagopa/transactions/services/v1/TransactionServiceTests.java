package it.pagopa.transactions.services.v1;

import it.pagopa.ecommerce.commons.client.QueueAsyncClient;
import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.PaymentTransferInformation;
import it.pagopa.ecommerce.commons.documents.v1.*;
import it.pagopa.ecommerce.commons.documents.v2.ClosureErrorData;
import it.pagopa.ecommerce.commons.queues.TracingUtils;
import it.pagopa.ecommerce.commons.redis.templatewrappers.ExclusiveLockDocumentWrapper;
import it.pagopa.ecommerce.commons.redis.templatewrappers.v2.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.ecommerce.commons.redis.templatewrappers.UniqueIdTemplateWrapper;
import it.pagopa.ecommerce.commons.utils.v2.JwtTokenUtils;
import it.pagopa.ecommerce.commons.utils.OpenTelemetryUtils;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.PaymentGatewayClient;
import it.pagopa.transactions.client.WalletClient;
import it.pagopa.transactions.commands.TransactionRequestAuthorizationCommand;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.data.redis.AutoConfigureDataRedis;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Stream;

import static it.pagopa.generated.transactions.v2.server.model.OutcomeNpgGatewayDto.OperationResultEnum.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@WebFluxTest
@TestPropertySource(locations = "classpath:application-tests.properties")
@Import(
    {
            it.pagopa.transactions.services.v1.TransactionsService.class,
            it.pagopa.transactions.services.v2.TransactionsService.class,
            it.pagopa.transactions.services.v2_1.TransactionsService.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionRequestAuthorizationHandler.class,
            it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler.class,
            it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionSendClosureRequestHandler.class,
            it.pagopa.transactions.projections.handlers.v2.ClosureRequestedProjectionHandler.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler.class,
            it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler.class,
            it.pagopa.transactions.commands.handlers.v2.TransactionRequestUserReceiptHandler.class,
            it.pagopa.transactions.projections.handlers.v2.TransactionUserReceiptProjectionHandler.class,
            it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler.class,
            TransactionsEventStoreRepository.class,
            UUIDUtils.class
    }
)
@AutoConfigureDataRedis
class TransactionServiceTests {
    @MockBean
    private TransactionsViewRepository repository;

    @Autowired
    @Qualifier(it.pagopa.transactions.services.v1.TransactionsService.QUALIFIER_NAME)
    private it.pagopa.transactions.services.v1.TransactionsService transactionsServiceV1;

    @Autowired
    private UUIDUtils uuidUtils;

    @MockBean
    private EcommercePaymentMethodsClient ecommercePaymentMethodsClient;

    @MockBean
    private WalletClient walletClient;

    @MockBean
    private PaymentGatewayClient paymentGatewayClient;

    @MockBean
    private NodeForPspClient nodeForPspClient;

    @MockBean
    @Qualifier("transactionClosureRetryQueueAsyncClientV1")
    private QueueAsyncClient queueAsyncClientClosureRetryV1;

    @MockBean
    @Qualifier("transactionRefundQueueAsyncClientV1")
    private QueueAsyncClient queueAsyncClientRefundV1;

    @MockBean
    @Qualifier("transactionClosureRetryQueueAsyncClientV2")
    private QueueAsyncClient queueAsyncClientClosureRetryV2;

    @MockBean
    @Qualifier("transactionClosureQueueAsyncClientV2")
    private QueueAsyncClient transactionClosureQueueAsyncClientV2;

    @MockBean
    private it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler transactionActivateHandlerV2;

    @MockBean
    private it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler transactionCancelHandlerV2;

    @MockBean
    private it.pagopa.transactions.commands.handlers.v2.TransactionRequestAuthorizationHandler transactionRequestAuthorizationHandlerV2;

    @MockBean
    private it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandlerV2;

    @MockBean
    private it.pagopa.transactions.commands.handlers.v2.TransactionRequestUserReceiptHandler transactionUpdateStatusHandlerV2;

    @MockBean
    private it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandlerV2;

    @MockBean
    private it.pagopa.transactions.projections.handlers.v2.TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandlerV2;

    @MockBean
    private it.pagopa.transactions.projections.handlers.v2.ClosureRequestedProjectionHandler closureRequestedProjectionHandler;

    @MockBean
    private TransactionsEventStoreRepository transactionsEventStoreRepository;

    @MockBean
    private it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler cancellationRequestProjectionHandlerV2;

    @Captor
    private ArgumentCaptor<TransactionRequestAuthorizationCommand> commandArgumentCaptor;

    @MockBean
    private JwtTokenUtils jwtTokenUtils;

    @MockBean
    private TransactionsUtils transactionsUtils;

    @MockBean
    private AuthRequestDataUtils authRequestDataUtils;

    @MockBean
    private TracingUtils tracingUtils;

    @MockBean
    private PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper;

    @MockBean
    private UniqueIdTemplateWrapper uniqueIdTemplateWrapper;

    @MockBean
    private UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils;

    @MockBean
    private OpenTelemetryUtils openTelemetryUtils;

    @MockBean
    private ConfidentialMailUtils confidentialMailUtils;

    @MockBean
    private ExclusiveLockDocumentWrapper exclusiveLockDocumentWrapper;

    final String TRANSACTION_ID = TransactionTestUtils.TRANSACTION_ID;

    @Test
    void getTransactionReturnsTransactionDataOriginProvided() {

        final Transaction transaction = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );

        transaction.setPaymentGateway("VPOS");
        transaction.setSendPaymentResultOutcome(
                TransactionUserReceiptData.Outcome.OK
        );
        transaction.setAuthorizationCode("00");
        transaction.setAuthorizationErrorCode(null);

        final TransactionInfoDto expected = new TransactionInfoDto()
                .transactionId(TRANSACTION_ID)
                .payments(
                        transaction.getPaymentNotices().stream().map(
                                p -> new PaymentInfoDto()
                                        .paymentToken(p.getPaymentToken())
                                        .rptId(p.getRptId())
                                        .reason(p.getDescription())
                                        .amount(p.getAmount())
                                        .isAllCCP(p.isAllCCP())
                                        .transferList(
                                                p.getTransferList().stream().map(
                                                        notice -> new TransferDto()
                                                                .paFiscalCode(notice.getPaFiscalCode())
                                                                .digitalStamp(notice.getDigitalStamp())
                                                                .transferAmount(notice.getTransferAmount())
                                                                .transferCategory(notice.getTransferCategory())
                                                ).toList()
                                        )
                        ).toList()
                )
                .clientId(TransactionInfoDto.ClientIdEnum.CHECKOUT)
                .feeTotal(null)
                .status(TransactionStatusDto.ACTIVATED)
                .idCart("ecIdCart")
                .gateway("VPOS")
                .sendPaymentResultOutcome(TransactionInfoDto.SendPaymentResultOutcomeEnum.OK)
                .authorizationCode("00")
                .errorCode(null);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        when(transactionsUtils.convertEnumerationV1(any())).thenCallRealMethod();
        assertEquals(
                transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, null).block(),
                expected
        );

        StepVerifier.create(transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void getTransactionReturnsTransactionDataOriginProvidedNoAdditionalFields() {

        final Transaction transaction = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );

        transaction.setPaymentGateway(null);
        transaction.setSendPaymentResultOutcome(null);
        transaction.setAuthorizationCode(null);
        transaction.setAuthorizationErrorCode(null);

        final TransactionInfoDto expected = new TransactionInfoDto()
                .transactionId(TRANSACTION_ID)
                .payments(
                        transaction.getPaymentNotices().stream().map(
                                p -> new PaymentInfoDto()
                                        .paymentToken(p.getPaymentToken())
                                        .rptId(p.getRptId())
                                        .reason(p.getDescription())
                                        .amount(p.getAmount())
                                        .isAllCCP(p.isAllCCP())
                                        .transferList(
                                                p.getTransferList().stream().map(
                                                        notice -> new TransferDto()
                                                                .paFiscalCode(notice.getPaFiscalCode())
                                                                .digitalStamp(notice.getDigitalStamp())
                                                                .transferAmount(notice.getTransferAmount())
                                                                .transferCategory(notice.getTransferCategory())
                                                ).toList()
                                        )
                        ).toList()
                )
                .clientId(TransactionInfoDto.ClientIdEnum.CHECKOUT)
                .feeTotal(null)
                .status(TransactionStatusDto.ACTIVATED)
                .idCart("ecIdCart")
                .gateway(null)
                .sendPaymentResultOutcome(null)
                .authorizationCode(null)
                .errorCode(null);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        when(transactionsUtils.convertEnumerationV1(any())).thenCallRealMethod();
        assertEquals(
                transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, null).block(),
                expected
        );

        StepVerifier.create(transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void getTransactionThrowsOnTransactionNotFound() {
        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.empty());

        assertThrows(
                TransactionNotFoundException.class,
                () -> transactionsServiceV1.getTransactionInfo(TRANSACTION_ID, null).block(),
                TRANSACTION_ID
        );
    }

    @Test
    void getPaymentTokenByTransactionNotFound() {

        TransactionNotFoundException exception = new TransactionNotFoundException(TRANSACTION_ID);

        assertEquals(
                exception.getPaymentToken(),
                TRANSACTION_ID
        );
    }

    @Test
    void shouldReturnNotFoundForNonExistingRequest() {
        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(100)
                .fee(0)
                .paymentInstrumentId("paymentInstrumentId")
                .isAllCCP(false)
                .pspId("pspId");

        /* preconditions */
        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.empty());

        /* test */
        Mono<RequestAuthorizationResponseDto> requestAuthorizationResponseDtoMono = transactionsServiceV1
                .requestTransactionAuthorization(TRANSACTION_ID, null, null, null, authorizationRequest);
        assertThrows(
                TransactionNotFoundException.class,
                requestAuthorizationResponseDtoMono::block
        );
        // verify that cache delete is never called
        verify(paymentRequestInfoRedisTemplateWrapper, times(0)).deleteById(any());
    }

    @Test
    void shouldReturnNotFoundExceptionForNonExistingToAddUserReceipt() {
        AddUserReceiptRequestDto addUserReceiptRequest = new AddUserReceiptRequestDto()
                .outcome(AddUserReceiptRequestDto.OutcomeEnum.OK)
                .paymentDate(OffsetDateTime.now())
                .addPaymentsItem(
                        new AddUserReceiptRequestPaymentsInnerDto()
                                .paymentToken("paymentToken")
                                .companyName("companyName")
                                .creditorReferenceId("creditorReferenceId")
                                .description("description")
                                .debtor("debtor")
                                .fiscalCode("fiscalCode")
                                .officeName("officeName")
                );

        /* preconditions */
        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.empty());

        /* test */
        StepVerifier.create(transactionsServiceV1.addUserReceipt(TRANSACTION_ID, addUserReceiptRequest))
                .expectErrorMatches(TransactionNotFoundException.class::isInstance)
                .verify();
    }

    static Stream<Arguments> v2ClientIdMapping() {
        return Stream.of(
                Arguments.of("IO", it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.IO),
                Arguments.of("CHECKOUT", it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT),
                Arguments.of(
                        "CHECKOUT_CART",
                        it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT_CART
                ),
                Arguments.of(
                        "CHECKOUT_CART",
                        it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.WISP_REDIRECT
                )
        );
    }

    @ParameterizedTest
    @EnumSource(Transaction.ClientId.class)
    void shouldConvertClientIdSuccessfully(Transaction.ClientId clientId) {
        assertEquals(clientId.name(), transactionsServiceV1.convertClientId(clientId).toString());
    }

    @ParameterizedTest
    @MethodSource("v2ClientIdMapping")
    void shouldConvertV2ClientIdSuccessfully(
                                             String expected,
                                             it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId clientId
    ) {
        assertEquals(expected, transactionsServiceV1.convertClientId(clientId).toString());
    }

    @Test
    void shouldRejectNullClientId() {
        assertThrows(
                InvalidRequestException.class,
                () -> transactionsServiceV1
                        .convertClientId((it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId) null)
        );
    }

    @Test
    void shouldThrowsInvalidRequestExceptionForInvalidClientID() {
        it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId clientId = Mockito
                .mock(it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.class);
        Mockito.when(clientId.toString()).thenReturn("InvalidClientID");
        assertThrows(InvalidRequestException.class, () -> transactionsServiceV1.convertClientId(clientId));
    }

    @Test
    void shouldThrowPaymentMethodNotFoundExceptionForPaymentMethodNotFound() {
        Transaction transaction = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );

        RequestAuthorizationRequestDto authorizationRequest = new RequestAuthorizationRequestDto()
                .amount(0)
                .paymentInstrumentId("paymentInstrumentId")
                .language(RequestAuthorizationRequestDto.LanguageEnum.IT)
                .fee(0)
                .pspId("PSP_CODE")
                .isAllCCP(false)
                .details(new ApmAuthRequestDetailsDto().detailType("apm"));

        /* preconditions */

        PaymentMethodNotFoundException exception = new PaymentMethodNotFoundException(
                UUID.randomUUID().toString(),
                "CHECKOUT"
        );

        Mockito.when(ecommercePaymentMethodsClient.getPaymentMethod(any(), any())).thenReturn(Mono.error(exception));

        Mockito.when(repository.findById(TRANSACTION_ID))
                .thenReturn(Mono.just(transaction));

        /* test */

        StepVerifier.create(
                transactionsServiceV1
                        .requestTransactionAuthorization(TRANSACTION_ID, null, null, null, authorizationRequest)
        )
                .expectError(PaymentMethodNotFoundException.class)
                .verify();
    }

    @Test
    void shouldExecuteTransactionUserCancelKONotFound() {
        String transactionId = UUID.randomUUID().toString();
        when(repository.findById(transactionId)).thenReturn(Mono.empty());
        StepVerifier.create(transactionsServiceV1.cancelTransaction(transactionId, null))
                .expectError(TransactionNotFoundException.class).verify();

    }

    private static Stream<Arguments> getTransactionStatusForFinalOutcomeWithoutPaymentGatewayLogic() {
        return Stream.of(
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED,
                        null,
                        new TransactionOutcomeInfoDto()
                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1)
                                .isFinalStatus(false)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.NOTIFIED_OK,
                        50,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_0)
                                .totalAmount(100)
                                .fees(50)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.NOTIFIED_KO,
                        50,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.REFUNDED,
                        50,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.EXPIRED_NOT_AUTHORIZED,
                        50,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_4)
                                .isFinalStatus(false)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CANCELED,
                        50,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_8)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CANCELLATION_EXPIRED,
                        50,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_8)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_REQUESTED,
                        50,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_17)
                                .isFinalStatus(false)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.REFUND_ERROR,
                        50,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.REFUND_REQUESTED,
                        50,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1)
                                .isFinalStatus(true)
                )
        );
    }

    @Test
    void getTransactionOutcomeReturnsOutcomesForStatusesNotifiedOKAndRightTotalAmount() {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.NOTIFIED_OK,
                        ZonedDateTime.now()
                );

        transaction.setUserId(null);

        transaction.setFeeTotal(50);

        PaymentNotice p1 = new PaymentNotice(
                "paymentToken",
                "77777777777111111111111111111",
                "description",
                100,
                "paymentContextCode",
                List.of(new PaymentTransferInformation("transferPAFiscalCode", false, 100, "transferCategory")),
                false,
                "companyName",
                "222222222222"
        );
        PaymentNotice p2 = new PaymentNotice(
                "paymentToken",
                "77777777777111111111111111112",
                "description",
                200,
                "paymentContextCode2",
                List.of(new PaymentTransferInformation("transferPAFiscalCode", false, 100, "transferCategory")),
                false,
                "companyName",
                "222222222222"
        );

        transaction.setPaymentNotices(List.of(p1, p2));

        TransactionOutcomeInfoDto expected = new TransactionOutcomeInfoDto()
                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_0)
                .totalAmount(300)
                .fees(50)
                .isFinalStatus(true);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                expected,
                transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block()
        );

        StepVerifier
                .create(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
    }

    /**
     * Test cases and expected outcomes are
     * <p>
     * Transaction status: ACTIVATED expected outcome: 1 final status: false <br/>
     * Transaction status: NOTIFIED_OK expected outcome: 0 final status: true <br/>
     * Transaction status: NOTIFIED_OK expected outcome: 0 final status: true <br/>
     * Transaction status: REFUNDED expected outcome: 25 final status: true <br/>
     * Transaction status: EXPIRED_NOT_AUTHORIZED expected outcome: 4 final status:
     * false <br/>
     * Transaction status: CANCELED expected outcome: 8 final status: true <br/>
     * Transaction status: CANCELLATION_EXPIRED expected outcome: 8 final status:
     * true <br/>
     * Transaction status: AUTHORIZATION_REQUESTED expected outcome: 17 final
     * status: false <br/>
     * Transaction status: REFUND_ERROR expected outcome: 1 final status: true <br/>
     * Transaction status: REFUND_REQUESTED expected outcome: 1 final status: true
     * <br/>
     * </p>
     */

    @ParameterizedTest
    @MethodSource("getTransactionStatusForFinalOutcomeWithoutPaymentGatewayLogic")
    void getTransactionOutcomeReturnsOutcomesForStatusesWithoutAnyOtherCondition(
                                                                                 it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto status,
                                                                                 Integer fees,
                                                                                 TransactionOutcomeInfoDto expected
    ) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        status,
                        ZonedDateTime.now()
                );

        transaction.setUserId(null);
        transaction.setFeeTotal(fees);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                expected,
                transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block()
        );

        StepVerifier
                .create(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
    }

    private static Stream<Arguments> getTransactionStatusForFinalOutcomesForSendPaymentResultConditionedLogic() {
        return Stream.of(
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.NOTIFICATION_REQUESTED,
                        null,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.NOTIFICATION_REQUESTED,
                        it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome.KO,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.NOTIFICATION_REQUESTED,
                        it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome.NOT_RECEIVED,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.NOTIFICATION_REQUESTED,
                        it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome.OK,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_0)
                                .isFinalStatus(true)
                                .totalAmount(100)
                                .fees(50)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.NOTIFICATION_ERROR,
                        null,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.NOTIFICATION_ERROR,
                        it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome.KO,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.NOTIFICATION_ERROR,
                        it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome.NOT_RECEIVED,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.NOTIFICATION_ERROR,
                        it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome.OK,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_0)
                                .isFinalStatus(true)
                                .totalAmount(100)
                                .fees(50)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSED,
                        null,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1)
                                .isFinalStatus(false)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSED,
                        it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome.KO,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1)
                                .isFinalStatus(false)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSED,
                        it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome.NOT_RECEIVED,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_17)
                                .isFinalStatus(false)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSED,
                        it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome.OK,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1)
                                .isFinalStatus(false)
                )
        );
    }

    /**
     * Test cases and expected outcomes are
     * <p>
     * Transaction status: NOTIFICATION_REQUESTED sendPaymentResult null expected
     * outcome: 25 final status: true <br/>
     * Transaction status: NOTIFICATION_REQUESTED sendPaymentResult KO expected
     * outcome: 25 final status: true <br/>
     * Transaction status: NOTIFICATION_REQUESTED sendPaymentResult NOT_RECEIVED
     * expected outcome: 25 final status: true <br/>
     * Transaction status: NOTIFICATION_REQUESTED sendPaymentResult OK expected
     * outcome: 0 final status: true <br/>
     * Transaction status: NOTIFICATION_ERROR sendPaymentResult null expected
     * outcome: 25 final status: true <br/>
     * Transaction status: NOTIFICATION_ERROR sendPaymentResult KO expected outcome:
     * 25 final status: true <br/>
     * Transaction status: NOTIFICATION_ERROR sendPaymentResult NOT_RECEIVED
     * expected outcome: 25 final status: true <br/>
     * Transaction status: NOTIFICATION_ERROR sendPaymentResult OK expected outcome:
     * 0 final status: true <br/>
     * Transaction status: CLOSED sendPaymentResult null expected outcome: 1 final
     * status: false <br/>
     * Transaction status: CLOSED sendPaymentResult KO expected outcome: 1 final
     * status: false <br/>
     * Transaction status: CLOSED sendPaymentResult NOT_RECEIVED expected outcome:
     * 17 final status: false <br/>
     * Transaction status: CLOSED sendPaymentResult OK expected outcome: 1 final
     * status: false
     * </p>
     */

    @ParameterizedTest
    @MethodSource("getTransactionStatusForFinalOutcomesForSendPaymentResultConditionedLogic")
    void getTransactionOutcomeReturnsOutcomesForSendPaymentResultConditionedLogic(
                                                                                  it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto status,
                                                                                  it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome sendPaymentResultOutcomeEnum,
                                                                                  TransactionOutcomeInfoDto expected
    ) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        status,
                        ZonedDateTime.now()
                );
        transaction.setUserId(null);
        transaction.setFeeTotal(50);
        transaction.setSendPaymentResultOutcome(sendPaymentResultOutcomeEnum);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                expected,
                transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block()
        );

        StepVerifier
                .create(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
    }

    private static Stream<Arguments> getTransactionStatusForFinalOutcomesForGatewayOutcomeConditionedLogic() {
        return Stream.of(
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "REDIRECT",
                        null,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "REDIRECT",
                        "OK",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1)
                                .isFinalStatus(false)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "REDIRECT",
                        "KO",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "REDIRECT",
                        "CANCELED",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_8)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "REDIRECT",
                        "ERROR",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "REDIRECT",
                        "EXPIRED",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "REDIRECT",
                        null,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "REDIRECT",
                        "OK",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1)
                                .isFinalStatus(false)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "REDIRECT",
                        "KO",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "REDIRECT",
                        "CANCELED",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_8)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "REDIRECT",
                        "ERROR",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "REDIRECT",
                        "EXPIRED",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "REDIRECT",
                        null,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "REDIRECT",
                        "OK",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_17)
                                .isFinalStatus(false)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "REDIRECT",
                        "KO",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "REDIRECT",
                        "CANCELED",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_8)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "REDIRECT",
                        "ERROR",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "REDIRECT",
                        "EXPIRED",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "REDIRECT",
                        null,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "REDIRECT",
                        "OK",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "REDIRECT",
                        "KO",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "REDIRECT",
                        "CANCELED",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_8)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "REDIRECT",
                        "EXPIRED",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "REDIRECT",
                        "ERROR",
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "NPG",
                        null,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "NPG",
                        EXECUTED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1)
                                .isFinalStatus(false)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "NPG",
                        CANCELED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_8)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "NPG",
                        DENIED_BY_RISK.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "NPG",
                        THREEDS_VALIDATED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "NPG",
                        THREEDS_FAILED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "NPG",
                        AUTHORIZED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "NPG",
                        PENDING.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "NPG",
                        VOIDED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "NPG",
                        REFUNDED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "NPG",
                        FAILED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        "NPG",
                        DECLINED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "NPG",
                        null,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "NPG",
                        EXECUTED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1)
                                .isFinalStatus(false)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "NPG",
                        CANCELED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_8)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "NPG",
                        DENIED_BY_RISK.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "NPG",
                        THREEDS_VALIDATED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "NPG",
                        THREEDS_FAILED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "NPG",
                        AUTHORIZED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "NPG",
                        PENDING.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "NPG",
                        VOIDED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "NPG",
                        REFUNDED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "NPG",
                        FAILED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        "NPG",
                        DECLINED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "NPG",
                        null,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "NPG",
                        EXECUTED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_17)
                                .isFinalStatus(false)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "NPG",
                        CANCELED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_8)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "NPG",
                        DENIED_BY_RISK.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "NPG",
                        THREEDS_VALIDATED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "NPG",
                        THREEDS_FAILED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "NPG",
                        AUTHORIZED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "NPG",
                        PENDING.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "NPG",
                        VOIDED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "NPG",
                        REFUNDED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "NPG",
                        FAILED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        "NPG",
                        DECLINED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "NPG",
                        null,
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "NPG",
                        EXECUTED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "NPG",
                        CANCELED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_8)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "NPG",
                        DENIED_BY_RISK.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "NPG",
                        THREEDS_VALIDATED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "NPG",
                        THREEDS_FAILED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "NPG",
                        AUTHORIZED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "NPG",
                        PENDING.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "NPG",
                        VOIDED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "NPG",
                        REFUNDED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "NPG",
                        FAILED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                ),
                Arguments.of(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED,
                        "NPG",
                        DECLINED.getValue(),
                        new TransactionOutcomeInfoDto().outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                .isFinalStatus(true)
                )

        );
    }

    /**
     * Test cases and expected outcomes are
     * <p>
     * Transaction status: CLOSURE_ERROR paymentGateway REDIRECT authorizationStatus
     * null expected outcome: 25 final status: true <br/>
     * Transaction status: CLOSURE_ERROR paymentGateway REDIRECT authorizationStatus
     * OK expected outcome: 1 final status: false <br/>
     * Transaction status: CLOSURE_ERROR paymentGateway REDIRECT authorizationStatus
     * KO expected outcome: 2 final status: true <br/>
     * Transaction status: CLOSURE_ERROR paymentGateway REDIRECT authorizationStatus
     * CANCELED expected outcome: 8 final status: true <br/>
     * Transaction status: CLOSURE_ERROR paymentGateway REDIRECT authorizationStatus
     * ERROR expected outcome: 25 final status: true <br/>
     * Transaction status: CLOSURE_ERROR paymentGateway REDIRECT authorizationStatus
     * EXPIRED expected outcome: 25 final status: true <br/>
     * <p>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway REDIRECT
     * authorizationStatus null expected outcome: 25 final status: true <br/>
     * Transactio status: AUTHORIZATION_COMPLETED paymentGateway REDIRECT
     * authorizationStatu OK expected outcome: 1 final status: false <br/>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway REDIRECT
     * authorizationStatus KO expected outcome: 2 final status: true <br/>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway REDIRECT
     * authorizationStatus CANCELE expected outcome: 8 final status: true <br/>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway REDIRECT
     * authorizationStatus ERROR expected outcome: 25 final status: true <br/>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway REDIRECT
     * authorizationStatus EXPIRED expected outcome: 25 final status: true <br/>
     * <p>
     * Transaction status: CLOSURE_REQUESTED paymentGateway REDIRECT
     * authorizationStatus null expected outcome: 25 final status: true <br/>
     * Transaction status: CLOSURE_REQUESTED paymentGateway REDIRECT
     * authorizationStatus OK expected outcome: 17 final status: false <br/>
     * Transaction status: CLOSURE_REQUESTED paymentGateway REDIRECT
     * authorizationStatus KO expected outcome: 2 final status: true <br/>
     * Transaction status: CLOSURE_REQUESTED paymentGateway REDIRECT
     * authorizationStatus CANCELED expected outcome: 8 final status: true <br/>
     * Transaction status: CLOSURE_REQUESTED paymentGateway REDIRECT
     * authorizationStatus ERROR expected outcome: 25 final status: true <br/>
     * Transaction status: CLOSURE_REQUESTED paymentGateway REDIRECT
     * authorizationStatus EXPIRED expected outcome: 25 final status: true <br/>
     * <p>
     * Transaction status: UNAUTHORIZED paymentGateway REDIRECT authorizationStatus
     * null expected outcome: 25 final status: true <br/>
     * Transaction status: UNAUTHORIZED paymentGateway REDIRECT authorizationStatus
     * OK expected outcome: 25 final status: true <br/>
     * Transaction status: UNAUTHORIZED paymentGateway REDIRECT authorizationStatus
     * KO expected outcome: 2 final status: true <br/>
     * Transaction status: UNAUTHORIZED paymentGateway REDIRECT authorizationStatus
     * CANCELED expected outcome: 8 final status: true <br/>
     * Transaction status: UNAUTHORIZED paymentGateway REDIRECT authorizationStatus
     * EXPIRED expected outcome: 25 final status: true <br/>
     * Transaction status: UNAUTHORIZED paymentGateway REDIRECT authorizationStatus
     * ERROR expected outcome: 25 final status: true <br/>
     * <p>
     * Transaction status: CLOSURE_ERROR paymentGateway NPG authorizationStatus null
     * expected outcome: 25 final status: true <br/>
     * Transaction status: CLOSURE_ERROR paymentGateway NPG authorizationStatus
     * EXECUTED expected outcome: 1 final status: false <br/>
     * Transaction status: CLOSURE_ERROR paymentGateway NPG authorizationStatus
     * CANCELED expected outcome: 8 final status: true <br/>
     * Transaction status: CLOSURE_ERROR paymentGateway NPG authorizationStatus
     * DENIED_BY_RISK expected outcome: 2 final status: true <br/>
     * Transaction status: CLOSURE_ERROR paymentGateway NPG authorizationStatus
     * THREEDS_VALIDATED expected outcome: 2 final status: true <br/>
     * Transaction status: CLOSURE_ERROR paymentGateway NPG authorizationStatus
     * THREEDS_FAILED expected outcome: 2 final status: true <br/>
     * Transaction status: CLOSURE_ERROR paymentGateway NPG authorizationStatus
     * AUTHORIZED expected outcome: 25 final status: true <br/>
     * Transaction status: CLOSURE_ERROR paymentGateway NPG authorizationStatus
     * PENDING expected outcome: 25 final status: true <br/>
     * Transaction status: CLOSURE_ERROR paymentGateway NPG authorizationStatus
     * VOIDED expected outcome: 25 final status: true <br/>
     * Transaction status: CLOSURE_ERROR paymentGateway NPG authorizationStatus
     * REFUNDED expected outcome: 25 final status: true <br/>
     * Transaction status: CLOSURE_ERROR paymentGateway NPG authorizationStatus
     * FAILED expected outcome: 25 final status: true <br/>
     * Transaction status: CLOSURE_ERROR paymentGateway NPG authorizationStatus
     * DECLINED expected outcome: 25 final status: true <br/>
     * <p>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway NPG
     * authorizationStatus null expected outcome: 25 final status: true <br/>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway NPG
     * authorizationStatus EXECUTED expected outcome: 1 final status: false <br/>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway NPG
     * authorizationStatus CANCELED expected outcome: 8 final status: true <br/>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway NPG
     * authorizationStatus DENIED_BY_RISK expected outcome: 2 final status: true
     * <br/>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway NPG
     * authorizationStatus THREEDS_VALIDATED expected outcome: 2 final status: true
     * <br/>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway NPG
     * authorizationStatus THREEDS_FAILED expected outcome: 2 final status: true
     * <br/>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway NPG
     * authorizationStatus AUTHORIZED expected outcome: 25 final status: true <br/>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway NPG
     * authorizationStatus PENDING expected outcome: 25 final status: true <br/>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway NPG
     * authorizationStatus VOIDED expected outcome: 25 final status: true <br/>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway NPG
     * authorizationStatus REFUNDED expected outcome: 25 final status: true <br/>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway NPG
     * authorizationStatus FAILED expected outcome: 25 final status: true <br/>
     * Transaction status: AUTHORIZATION_COMPLETED paymentGateway NPG
     * authorizationStatus DECLINED expected outcome: 25 final status: true <br/>
     * <p>
     * Transaction status: CLOSURE_REQUESTED paymentGateway NPG authorizationStatus
     * null expected outcome: 25 final status: true <br/>
     * Transaction status: CLOSURE_REQUESTED paymentGateway NPG authorizationStatus
     * EXECUTED expected outcome: 17 final status: false <br/>
     * Transaction status: CLOSURE_REQUESTED paymentGateway NPG authorizationStatus
     * CANCELED expected outcome: 8 final status: true <br/>
     * Transaction status: CLOSURE_REQUESTED paymentGateway NPG authorizationStatus
     * DENIED_BY_RISK expected outcome: 2 final status: true <br/>
     * Transaction status: CLOSURE_REQUESTED paymentGateway NPG authorizationStatus
     * THREEDS_VALIDATED expected outcome: 2 final status: true <br/>
     * Transaction status: CLOSURE_REQUESTED paymentGateway NPG authorizationStatus
     * THREEDS_VALIDATED expected outcome: 2 final status: true <br/>
     * Transaction status: CLOSURE_REQUESTED paymentGateway NPG authorizationStatus
     * AUTHORIZED expected outcome: 25 final status: true <br/>
     * Transaction status: CLOSURE_REQUESTED paymentGateway NPG authorizationStatus
     * PENDING expected outcome: 25 final status: true <br/>
     * Transaction status: CLOSURE_REQUESTED paymentGateway NPG authorizationStatus
     * VOIDED expected outcome: 25 final status: true <br/>
     * Transaction status: CLOSURE_REQUESTED paymentGateway NPG authorizationStatus
     * REFUNDED expected outcome: 25 final status: true <br/>
     * Transaction status: CLOSURE_REQUESTED paymentGateway NPG authorizationStatus
     * FAILED expected outcome: 25 final status: true <br/>
     * Transaction status: CLOSURE_REQUESTED paymentGateway NPG authorizationStatus
     * DECLINED expected outcome: 25 final status: true <br/>
     * <p>
     * Transaction status: UNAUTHORIZED paymentGateway NPG authorizationStatus null
     * expected outcome: 25 final status: true <br/>
     * Transaction status: UNAUTHORIZED paymentGateway NPG authorizationStatus
     * EXECUTED expected outcome: 25 final status: true <br/>
     * Transaction status: UNAUTHORIZED paymentGateway NPG authorizationStatus
     * CANCELED expected outcome: 8 final status: true <br/>
     * Transaction status: UNAUTHORIZED paymentGateway NPG authorizationStatus
     * DENIED_BY_RISK expected outcome: 2 final status: true <br/>
     * Transaction status: UNAUTHORIZED paymentGateway NPG authorizationStatus
     * THREEDS_VALIDATED expected outcome: 2 final status: true <br/>
     * Transaction status: UNAUTHORIZED paymentGateway NPG authorizationStatus
     * THREEDS_FAILED expected outcome: 2 final status: true <br/>
     * Transaction status: UNAUTHORIZED paymentGateway NPG authorizationStatus
     * AUTHORIZED expected outcome: 25 final status: true <br/>
     * Transaction status: UNAUTHORIZED paymentGateway NPG authorizationStatus
     * PENDING expected outcome: 25 final status: true <br/>
     * Transaction status: UNAUTHORIZED paymentGateway NPG authorizationStatus
     * VOIDED expected outcome: 25 final status: true <br/>
     * Transaction status: UNAUTHORIZED paymentGateway NPG authorizationStatus
     * REFUNDED expected outcome: 25 final status: true <br/>
     * Transaction status: UNAUTHORIZED paymentGateway NPG authorizationStatus
     * FAILED expected outcome: 25 final status: true <br/>
     * Transaction status: UNAUTHORIZED paymentGateway NPG authorizationStatus
     * DECLINED expected outcome: 25 final status: true <br/>
     */

    @ParameterizedTest
    @MethodSource("getTransactionStatusForFinalOutcomesForGatewayOutcomeConditionedLogic")
    void getTransactionOutcomeReturnsOutcomesForGatewayOutcomeConditionedLogic(
                                                                               it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto status,
                                                                               String paymentGateway,
                                                                               String gatewayAuthorizationStatus,
                                                                               TransactionOutcomeInfoDto expected
    ) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        status,
                        ZonedDateTime.now()
                );
        transaction.setPaymentGateway(paymentGateway);
        transaction.setGatewayAuthorizationStatus(gatewayAuthorizationStatus);
        transaction.setUserId(null);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                expected,
                transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block()
        );

        StepVerifier
                .create(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
    }

    private static Stream<Arguments> getTransactionStatusForFinalOutcomesForGatewayOutcomeConditionedLogicForDeniedStateAndSpecificErrorCode() {
        return Arrays.stream(
                new it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto[] {
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED,
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_REQUESTED,
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.UNAUTHORIZED
                }
        )
                .flatMap(
                        s -> Stream.of(
                                Arguments.of(
                                        s,
                                        "100",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "101",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_7)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "102",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "104",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_3)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "106",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "109",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "110",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_3)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "111",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_7)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "115",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "116",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_116)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "117",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_117)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "118",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_3)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "119",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "120",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "121",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_121)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "122",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "123",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "124",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "125",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_3)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "126",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "129",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "200",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "202",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "204",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "208",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_3)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "209",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_3)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "210",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_3)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "413",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "888",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "902",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "903",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "904",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "906",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "907",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "908",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "909",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "911",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "913",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                                .isFinalStatus(true)
                                ),
                                Arguments.of(
                                        s,
                                        "999",
                                        new TransactionOutcomeInfoDto()
                                                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25)
                                                .isFinalStatus(true)
                                )
                        )
                );
    }

    /**
     * NPG Cases only
     */
    @ParameterizedTest
    @MethodSource(
        "getTransactionStatusForFinalOutcomesForGatewayOutcomeConditionedLogicForDeniedStateAndSpecificErrorCode"
    )
    void getTransactionOutcomeReturnsOutcomesForGatewayOutcomeConditionedLogicForDeniedStateAndSpecificErrorCode(
                                                                                                                 it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto status,
                                                                                                                 String errorCode,
                                                                                                                 TransactionOutcomeInfoDto expected
    ) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        status,
                        ZonedDateTime.now()
                );
        transaction.setPaymentGateway("NPG");
        transaction.setGatewayAuthorizationStatus(DECLINED.getValue());
        transaction.setAuthorizationErrorCode(errorCode);
        transaction.setUserId(null);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                expected,
                transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block()
        );

        StepVerifier
                .create(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
    }

    private static Stream<Arguments> getTransactionStatusForFinalOutcomesForExpiredState() {

        Set<it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome> sendPaymentResultOutcomeSet = Set
                .of(
                        it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome.OK,
                        it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome.KO,
                        it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome.NOT_RECEIVED
                );

        Map<String, TransactionOutcomeInfoDto.OutcomeEnum> mapNpgGatewayAuthorizationStatusOutcome = new HashMap<>();
        mapNpgGatewayAuthorizationStatusOutcome.put("CANCELED", TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_8);
        mapNpgGatewayAuthorizationStatusOutcome.put("DENIED_BY_RISK", TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2);
        mapNpgGatewayAuthorizationStatusOutcome
                .put("THREEDS_VALIDATED", TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2);
        mapNpgGatewayAuthorizationStatusOutcome.put("THREEDS_FAILED", TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2);
        mapNpgGatewayAuthorizationStatusOutcome.put("AUTHORIZED", TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25);
        mapNpgGatewayAuthorizationStatusOutcome.put("PENDING", TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25);
        mapNpgGatewayAuthorizationStatusOutcome.put("VOIDED", TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25);
        mapNpgGatewayAuthorizationStatusOutcome.put("REFUNDED", TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25);
        mapNpgGatewayAuthorizationStatusOutcome.put("FAILED", TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25);

        Map<String, TransactionOutcomeInfoDto.OutcomeEnum> mapRedirectGatewayAuthorizationStatusOutcome = new HashMap<>();
        mapRedirectGatewayAuthorizationStatusOutcome.put("KO", TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_2);
        mapRedirectGatewayAuthorizationStatusOutcome.put("CANCELED", TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_8);
        mapRedirectGatewayAuthorizationStatusOutcome.put("ERROR", TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25);
        mapRedirectGatewayAuthorizationStatusOutcome.put("EXPIRED", TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25);

        Map<String, Map<String, TransactionOutcomeInfoDto.OutcomeEnum>> paymentGatewayOutcomeMap = new HashMap<>();
        paymentGatewayOutcomeMap.put("NPG", mapNpgGatewayAuthorizationStatusOutcome);
        paymentGatewayOutcomeMap.put("REDIRECT", mapRedirectGatewayAuthorizationStatusOutcome);

        return sendPaymentResultOutcomeSet.stream()
                .flatMap(
                        sendPaymentResultOutcome -> paymentGatewayOutcomeMap.keySet().stream()
                                .flatMap(
                                        pgKey -> paymentGatewayOutcomeMap.get(pgKey).entrySet().stream().map(
                                                e -> Arguments
                                                        .of(sendPaymentResultOutcome, pgKey, e.getKey(), e.getValue())
                                        )
                                )
                );
    }

    /**
     * test cases and expected outcome
     * <p>
     * Transaction status: EXPIRED sendPaymentResultOutcome: KO paymentGateway NPG
     * authorizationStatus THREEDS_VALIDATED expected outcome: 2 final status: true
     * <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: KO paymentGateway NPG
     * authorizationStatus THREEDS_FAILED expected outcome: 2 final status: true
     * <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: KO paymentGateway NPG
     * authorizationStatus REFUNDED expected outcome: 25 final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: KO paymentGateway NPG
     * authorizationStatus FAILED expected outcome: 25 final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: KO paymentGateway NPG
     * authorizationStatus DENIED_BY_RISK expected outcome: 2 final status: true
     * <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: KO paymentGateway NPG
     * authorizationStatus CANCELED expected outcome: 8 final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: KO paymentGateway NPG
     * authorizationStatus PENDING expected outcome: 25 final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: KO paymentGateway NPG
     * authorizationStatus PENDING expected outcome: 25 final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: KO paymentGateway NPG
     * authorizationStatus PENDING expected outcome: 25 final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: KO paymentGateway NPG
     * authorizationStatus PENDING expected outcome: 25 final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: KO paymentGateway
     * REDIRECT authorizationStatus KO expected outcome: 2 final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: KO paymentGateway
     * REDIRECT authorizationStatus CANCELED expected outcome: 8 final status: true
     * <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: KO paymentGateway
     * REDIRECT authorizationStatus EXPIRED expected outcome: 25 final status: true
     * <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: KO paymentGateway
     * REDIRECT authorizationStatus ERROR expected outcome: 25 final status: true
     * <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: NOT_RECEIVED
     * paymentGateway NPG authorizationStatus THREEDS_VALIDATED expected outcome: 2
     * final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: NOT_RECEIVED
     * paymentGateway NPG authorizationStatus THREEDS_FAILED expected outcome: 2
     * final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: NOT_RECEIVED
     * paymentGateway NPG authorizationStatus REFUNDED expected outcome: 25 final
     * status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: NOT_RECEIVED
     * paymentGateway NPG authorizationStatus FAILED expected outcome: 25 final
     * status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: NOT_RECEIVED
     * paymentGateway NPG authorizationStatus DENIED_BY_RISK expected outcome: 2
     * final status: true <br/>
     * Transaction status EXPIRED sendPaymentResultOutcome: NOT_RECEIVED
     * paymentGateway NPG authorizationStatus CANCELED expected outcome: 8 final
     * status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: NOT_RECEIVED
     * paymentGateway NPG authorizationStatus PENDING expected outcome: 25 final
     * status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: NOT_RECEIVED
     * paymentGateway NPG authorizationStatus AUTHORIZED expected outcome: 25 final
     * status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: NOT_RECEIVED
     * paymentGateway NPG authorizationStatus VOIDED expected outcome: 25 final
     * status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: NOT_RECEIVED
     * paymentGateway REDIRECT authorizationStatus KO expected outcome: 2 final
     * status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: NOT_RECEIVED
     * paymentGateway REDIRECT authorizationStatus CANCELED expected outcome: 8
     * final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: NOT_RECEIVED
     * paymentGateway REDIRECT authorizationStatus EXPIRED expected outcome: 25
     * final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: NOT_RECEIVED
     * paymentGateway REDIRECT authorizationStatus ERROR expected outcome: 25 final
     * status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: OK paymentGateway NPG
     * authorizationStatus THREEDS_VALIDATED expected outcome: 2 final status: true
     * <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: OK paymentGateway NPG
     * authorizationStatus THREEDS_VALIDATED expected outcome: 2 final status: true
     * <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: OK paymentGateway NPG
     * authorizationStatus REFUNDED expected outcome: 25 final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: OK paymentGateway NPG
     * authorizationStatus FAILED expected outcome: 25 final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: OK paymentGateway NPG
     * authorizationStatus DENIED_BY_RISK expected outcome: 2 final status: true
     * <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: OK paymentGateway NPG
     * authorizationStatus CANCELED expected outcome: 8 final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: OK paymentGateway NPG
     * authorizationStatus PENDING expected outcome: 25 final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: OK paymentGateway NPG
     * authorizationStatus AUTHORIZED expected outcome: 25 final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: OK paymentGateway NPG
     * authorizationStatus VOIDED expected outcome: 25 final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: OK paymentGateway
     * REDIRECT authorizationStatus KO expected outcome: 2 final status: true <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: OK paymentGateway
     * REDIRECT authorizationStatus CANCELED expected outcome: 8 final status: true
     * <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: OK paymentGateway
     * REDIRECT authorizationStatus EXPIRED expected outcome: 25 final status: true
     * <br/>
     * Transaction status: EXPIRED sendPaymentResultOutcome: OK paymentGateway
     * REDIRECT authorizationStatus ERROR expected outcome: 25 final status: true
     * <br/>
     * </p>
     */
    @ParameterizedTest
    @MethodSource("getTransactionStatusForFinalOutcomesForExpiredState")
    void getTransactionOutcomeReturnsOutcomesForExpiredStateAndNotExecutedNPGOutcome(
                                                                                     it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData.Outcome sendPaymentResultOutcome,
                                                                                     String paymentGateway,
                                                                                     String paymentGatewayAuthorizationStatus,
                                                                                     TransactionOutcomeInfoDto.OutcomeEnum expectedOutcome
    ) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.EXPIRED,
                        ZonedDateTime.now()
                );
        transaction.setGatewayAuthorizationStatus(
                paymentGatewayAuthorizationStatus
        );
        transaction.setSendPaymentResultOutcome(sendPaymentResultOutcome);
        transaction.setPaymentGateway(paymentGateway);
        transaction.setFeeTotal(50);
        transaction.setUserId(null);
        TransactionOutcomeInfoDto expected = new TransactionOutcomeInfoDto().outcome(expectedOutcome)
                .isFinalStatus(true);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                expected,
                transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block()
        );

        StepVerifier
                .create(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void getTransactionOutcomeReturnsOutcomesForExpiredStateAndExecutedNPGOutcome() {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.EXPIRED,
                        ZonedDateTime.now()
                );
        transaction.setGatewayAuthorizationStatus(EXECUTED.getValue());
        transaction.setPaymentGateway("NPG");
        transaction.setUserId(null);
        TransactionOutcomeInfoDto expected = new TransactionOutcomeInfoDto()
                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1).isFinalStatus(true);
        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                expected,
                transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block()
        );

        StepVerifier
                .create(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void getTransactionOutcomeReturnsOutcomesForExpiredStateAndExecutedNPGOutcomeButNoNPGGateway() {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.EXPIRED,
                        ZonedDateTime.now()
                );
        transaction.setGatewayAuthorizationStatus(EXECUTED.getValue());
        transaction.setPaymentGateway("REDIRECT");
        transaction.setUserId(null);
        TransactionOutcomeInfoDto expected = new TransactionOutcomeInfoDto()
                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25).isFinalStatus(true);
        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                expected,
                transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block()
        );

        StepVerifier
                .create(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void getTransactionOutcomeReturnsOutcomesForExpiredStateAndOkREDIRECTOutcome() {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.EXPIRED,
                        ZonedDateTime.now()
                );
        transaction.setGatewayAuthorizationStatus("OK");
        transaction.setPaymentGateway("REDIRECT");
        transaction.setUserId(null);
        TransactionOutcomeInfoDto expected = new TransactionOutcomeInfoDto()
                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1).isFinalStatus(true);
        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                expected,
                transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block()
        );

        StepVerifier
                .create(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
    }

    @Test
    void getTransactionOutcomeReturnsOutcomesForExpiredStateAndEOkREDIRECTOutcomeButNoREDIRECTGateway() {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.EXPIRED,
                        ZonedDateTime.now()
                );
        transaction.setGatewayAuthorizationStatus("OK");
        transaction.setPaymentGateway("NPG");
        transaction.setUserId(null);
        TransactionOutcomeInfoDto expected = new TransactionOutcomeInfoDto()
                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_25).isFinalStatus(true);
        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                expected,
                transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block()
        );

        StepVerifier
                .create(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
    }

    static Stream<Arguments> getAllGatewaysAndAuthorizationStatus() {
        Map<String, String> gatewaysAndOkAuth = new HashMap<>();
        gatewaysAndOkAuth.put("NPG", EXECUTED.getValue());
        gatewaysAndOkAuth.put("REDIRECT", "OK");
        return gatewaysAndOkAuth.entrySet().stream().map(e -> Arguments.of(e.getKey(), e.getValue()));
    }

    @ParameterizedTest
    @MethodSource("getAllGatewaysAndAuthorizationStatus")
    void checkOutcomeHasFinalStatusFlagWithClosureErrorData4xx(
                                                               String gateway,
                                                               String gatewayAuthorizationStatus
    ) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED, // non final
                                                                                                           // status
                        ZonedDateTime.now()
                );
        transaction.setGatewayAuthorizationStatus(gatewayAuthorizationStatus);
        transaction.setPaymentGateway(gateway);
        transaction.setUserId(null);
        ClosureErrorData closureErrorData = new ClosureErrorData();
        closureErrorData.setErrorDescription("errorDescription");
        closureErrorData.setErrorType(ClosureErrorData.ErrorType.KO_RESPONSE_RECEIVED);
        closureErrorData.setHttpErrorCode(HttpStatus.BAD_REQUEST); // 4xx
        transaction.setClosureErrorData(closureErrorData);
        TransactionOutcomeInfoDto expected = new TransactionOutcomeInfoDto()
                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_18).isFinalStatus(true);
        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                expected,
                transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block()
        );

        StepVerifier
                .create(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
    }

    @ParameterizedTest
    @MethodSource("getAllGatewaysAndAuthorizationStatus")
    void checkOutcomeHasFinalStatusFlagWithClosureErrorData4xxAndOutcome1BecauseDataIsNotComplete(
                                                                                                  String gateway,
                                                                                                  String gatewayAuthorizationStatus
    ) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED, // non
                                                                                                                         // final
                        // status
                        ZonedDateTime.now()
                );
        transaction.setGatewayAuthorizationStatus(gatewayAuthorizationStatus);
        transaction.setPaymentGateway(gateway);
        transaction.setUserId(null);
        ClosureErrorData closureErrorData = new ClosureErrorData();
        closureErrorData.setErrorDescription("errorDescription");
        closureErrorData.setErrorType(ClosureErrorData.ErrorType.KO_RESPONSE_RECEIVED);
        closureErrorData.setHttpErrorCode(HttpStatus.UNPROCESSABLE_ENTITY); // 4xx
        transaction.setClosureErrorData(closureErrorData);
        TransactionOutcomeInfoDto expected = new TransactionOutcomeInfoDto()
                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1).isFinalStatus(true);
        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                expected,
                transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block()
        );

        StepVerifier
                .create(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
    }

    @ParameterizedTest
    @MethodSource("getAllGatewaysAndAuthorizationStatus")
    void checkOutcomeHasFinalStatusFlagWithClosureErrorData5xx(
                                                               String gateway,
                                                               String gatewayAuthorizationStatus
    ) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.AUTHORIZATION_COMPLETED, // non
                                                                                                                         // final
                        // status
                        ZonedDateTime.now()
                );
        transaction.setGatewayAuthorizationStatus(gatewayAuthorizationStatus);
        transaction.setPaymentGateway(gateway);
        transaction.setUserId(null);
        ClosureErrorData closureErrorData = new ClosureErrorData();
        closureErrorData.setErrorDescription("errorDescription");
        closureErrorData.setErrorType(ClosureErrorData.ErrorType.KO_RESPONSE_RECEIVED);
        closureErrorData.setHttpErrorCode(HttpStatus.BAD_GATEWAY); // 4xx
        transaction.setClosureErrorData(closureErrorData);
        TransactionOutcomeInfoDto expected = new TransactionOutcomeInfoDto()
                .outcome(TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1).isFinalStatus(false);
        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                expected,
                transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block()
        );

        StepVerifier
                .create(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null))
                .expectNext(expected)
                .verifyComplete();
    }

    static Stream<Arguments> getAllFinalStatuses() {
        Set<String> finalStates = Set.of(
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
        return finalStates.stream().map(Arguments::of);
    }

    static Stream<Arguments> getAllMaybeFinalStatuses() {
        Set<String> possibleFinalStates = Set.of("AUTHORIZATION_COMPLETED", "CLOSURE_REQUESTED", "CLOSURE_ERROR");
        return possibleFinalStates.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("getAllFinalStatuses")
    void checkOutcomeHasFinalStatusFlagWithFinalStatus(
                                                       it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto statusDto
    ) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        statusDto, // non final status
                        ZonedDateTime.now()
                );
        transaction.setPaymentGateway("NPG");
        transaction.setUserId(null);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertTrue(
                Objects.requireNonNull(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block())
                        .getIsFinalStatus()
        );
    }

    @ParameterizedTest
    @MethodSource("getAllMaybeFinalStatuses")
    void checkOutcomeHasFinalStatusFlagTrueWithMaybeFinalStatusAndExecutedNPG(
                                                                              it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto statusDto
    ) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        statusDto, // non final status
                        ZonedDateTime.now()
                );
        transaction.setPaymentGateway("NPG");
        transaction.setGatewayAuthorizationStatus(EXECUTED.getValue());
        transaction.setUserId(null);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertFalse(
                Objects.requireNonNull(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block())
                        .getIsFinalStatus()
        );
    }

    @ParameterizedTest
    @MethodSource("getAllMaybeFinalStatuses")
    void checkOutcomeHasFinalStatusFlagTrueWithMaybeFinalStatusAndOKREDIRECT(
                                                                             it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto statusDto
    ) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        statusDto, // non final status
                        ZonedDateTime.now()
                );
        transaction.setPaymentGateway("REDIRECT");
        transaction.setGatewayAuthorizationStatus("OK");
        transaction.setUserId(null);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertFalse(
                Objects.requireNonNull(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block())
                        .getIsFinalStatus()
        );
    }

    @ParameterizedTest
    @MethodSource("getAllMaybeFinalStatuses")
    void checkOutcomeHasFinalStatusFlagFalseWithMaybeFinalStatusAndNPGNotExecuted(
                                                                                  it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto statusDto
    ) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        statusDto, // non final status
                        ZonedDateTime.now()
                );
        transaction.setPaymentGateway("NPG");
        transaction.setGatewayAuthorizationStatus("test");
        transaction.setUserId(null);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertTrue(
                Objects.requireNonNull(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block())
                        .getIsFinalStatus()
        );
    }

    @ParameterizedTest
    @MethodSource("getAllMaybeFinalStatuses")
    void checkOutcomeHasFinalStatusFlagFalseWithMaybeFinalStatusAndREDIRECTNotOK(
                                                                                 it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto statusDto
    ) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        statusDto, // non final status
                        ZonedDateTime.now()
                );
        transaction.setPaymentGateway("REDIRECT");
        transaction.setGatewayAuthorizationStatus("test");
        transaction.setUserId(null);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertTrue(
                Objects.requireNonNull(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block())
                        .getIsFinalStatus()
        );
    }

    static Stream<Arguments> getAllClosureErrorDataCaseOutcome18() {

        Set<ClosureErrorData> closureErrorDataSet = new HashSet<>();

        ClosureErrorData closureErrorDataNodeDidNotReceiveRptYet = new ClosureErrorData();
        closureErrorDataNodeDidNotReceiveRptYet.setHttpErrorCode(HttpStatus.UNPROCESSABLE_ENTITY);
        closureErrorDataNodeDidNotReceiveRptYet.setErrorDescription("Node did not receive RPT yet");
        closureErrorDataSet.add(closureErrorDataNodeDidNotReceiveRptYet);

        ClosureErrorData closureErrorDataBadReqeust = new ClosureErrorData();
        closureErrorDataBadReqeust.setHttpErrorCode(HttpStatus.BAD_REQUEST);
        closureErrorDataSet.add(closureErrorDataBadReqeust);

        ClosureErrorData closureErrorDataNotFound = new ClosureErrorData();
        closureErrorDataNotFound.setHttpErrorCode(HttpStatus.NOT_FOUND);
        closureErrorDataSet.add(closureErrorDataNotFound);

        return closureErrorDataSet.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("getAllClosureErrorDataCaseOutcome18")
    void checkOutcomeWithClosureErrorDataForNPGOutcome18(ClosureErrorData closureErrorData) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        ZonedDateTime.now()
                );
        transaction.setPaymentGateway("NPG");
        transaction.setGatewayAuthorizationStatus("EXECUTED");
        transaction.setUserId(null);
        transaction.setClosureErrorData(closureErrorData);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_18,
                Objects.requireNonNull(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block())
                        .getOutcome()
        );
    }

    @ParameterizedTest
    @MethodSource("getAllClosureErrorDataCaseOutcome18")
    void checkOutcomeWithClosureErrorDataForRedirectOutcome18(ClosureErrorData closureErrorData) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        ZonedDateTime.now()
                );
        transaction.setPaymentGateway("REDIRECT");
        transaction.setGatewayAuthorizationStatus("OK");
        transaction.setUserId(null);
        transaction.setClosureErrorData(closureErrorData);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_18,
                Objects.requireNonNull(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block())
                        .getOutcome()
        );
    }

    static Stream<Arguments> getAllClosureErrorDataCaseOutcome1() {

        Set<ClosureErrorData> closureErrorDataSet = new HashSet<>();

        ClosureErrorData closureErrorDataUnprocessableEntity = new ClosureErrorData();
        closureErrorDataUnprocessableEntity.setHttpErrorCode(HttpStatus.UNPROCESSABLE_ENTITY);
        closureErrorDataSet.add(closureErrorDataUnprocessableEntity);

        ClosureErrorData closureErrorDataBadGateway = new ClosureErrorData();
        closureErrorDataBadGateway.setHttpErrorCode(HttpStatus.BAD_GATEWAY);
        closureErrorDataSet.add(closureErrorDataBadGateway);

        ClosureErrorData closureErrorDataNotData = new ClosureErrorData();
        closureErrorDataSet.add(closureErrorDataNotData);

        return closureErrorDataSet.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("getAllClosureErrorDataCaseOutcome1")
    void checkOutcomeWithClosureErrorDataForNPGAndOutcome1(ClosureErrorData closureErrorData) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        ZonedDateTime.now()
                );
        transaction.setPaymentGateway("NPG");
        transaction.setGatewayAuthorizationStatus("EXECUTED");
        transaction.setUserId(null);
        transaction.setClosureErrorData(closureErrorData);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1,
                Objects.requireNonNull(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block())
                        .getOutcome()
        );
    }

    @ParameterizedTest
    @MethodSource("getAllClosureErrorDataCaseOutcome1")
    void checkOutcomeWithClosureErrorDataForREDIRECTAndOutcome1(ClosureErrorData closureErrorData) {
        final it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSURE_ERROR,
                        ZonedDateTime.now()
                );
        transaction.setPaymentGateway("REDIRECT");
        transaction.setGatewayAuthorizationStatus("OK");
        transaction.setUserId(null);
        transaction.setClosureErrorData(closureErrorData);

        when(repository.findById(TRANSACTION_ID)).thenReturn(Mono.just(transaction));
        assertEquals(
                TransactionOutcomeInfoDto.OutcomeEnum.NUMBER_1,
                Objects.requireNonNull(transactionsServiceV1.getTransactionOutcome(TRANSACTION_ID, null).block())
                        .getOutcome()
        );
    }

}
