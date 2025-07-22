package it.pagopa.transactions.services.v2;

import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.BaseTransactionView;
import it.pagopa.ecommerce.commons.documents.PaymentNotice;
import it.pagopa.ecommerce.commons.documents.PaymentTransferInformation;
import it.pagopa.ecommerce.commons.documents.v2.*;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.activation.EmptyTransactionGatewayActivationData;
import it.pagopa.ecommerce.commons.domain.v2.*;
import it.pagopa.ecommerce.commons.domain.v2.TransactionActivated;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManager;
import it.pagopa.ecommerce.commons.utils.ConfidentialDataManagerTest;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.generated.transactions.v2.server.model.*;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.NotImplementedException;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.AutoConfigureDataRedis;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static it.pagopa.ecommerce.commons.v2.TransactionTestUtils.EMAIL_STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    private final ConfidentialDataManager confidentialDataManager = ConfidentialDataManagerTest.getMock();

    private final ConfidentialMailUtils confidentialMailUtils = new ConfidentialMailUtils(confidentialDataManager);
    private static final Long MOCK_AMOUNT = 100L;

    @Autowired
    private final TransactionsService transactionsService = new TransactionsService(
            transactionActivateHandlerV2,
            transactionsActivationProjectionHandlerV2,
            transactionsUtils,
            confidentialMailUtils,
            transactionsViewRepository
    );

    @Test
    void shouldHandleNewTransactionTransactionActivated() {
        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT;
        UUID TEST_SESSION_TOKEN = UUID.randomUUID();
        UUID TEST_CPP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(EMAIL_STRING)
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto().rptId(TransactionTestUtils.RPT_ID).amount(MOCK_AMOUNT)
                );

        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail(TransactionTestUtils.EMAIL);
        transactionActivatedData
                .setPaymentNotices(
                        List.of(
                                new PaymentNotice(
                                        TransactionTestUtils.PAYMENT_TOKEN,
                                        null,
                                        "dest",
                                        0L,
                                        TEST_CPP.toString(),
                                        List.of(new PaymentTransferInformation("77777777777", false, 0L, null)),
                                        false,
                                        null,
                                        null
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
                        new it.pagopa.ecommerce.commons.domain.v2.PaymentNotice(
                                new PaymentToken(TransactionTestUtils.PAYMENT_TOKEN),
                                new RptId(TransactionTestUtils.RPT_ID),
                                new TransactionAmount(0),
                                new TransactionDescription("desc"),
                                new PaymentContextCode(TEST_CPP.toString()),
                                List.of(new PaymentTransferInfo("77777777777", false, MOCK_AMOUNT, null)),
                                false,
                                new CompanyName(null),
                                null
                        )
                ),
                TransactionTestUtils.EMAIL,
                "faultCode",
                "faultCodeString",
                Transaction.ClientId.CHECKOUT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new EmptyTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
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
                                UUID.randomUUID(),
                                new TransactionId(transactionActivatedEvent.getTransactionId()),
                                UUID.randomUUID()
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
    void shouldProjectCreditorReferenceIdWhenHandleNewTransactionTransactionActivatedForWispRedirect() {
        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT_CART;
        final var creditorReferenceId = UUID.randomUUID().toString();
        UUID TEST_SESSION_TOKEN = UUID.randomUUID();
        UUID TEST_CPP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(EMAIL_STRING)
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto().rptId(TransactionTestUtils.RPT_ID).amount(MOCK_AMOUNT)
                );

        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail(TransactionTestUtils.EMAIL);
        transactionActivatedData
                .setPaymentNotices(
                        List.of(
                                new PaymentNotice(
                                        TransactionTestUtils.PAYMENT_TOKEN,
                                        null,
                                        "dest",
                                        0L,
                                        TEST_CPP.toString(),
                                        List.of(new PaymentTransferInformation("77777777777", false, 0L, null)),
                                        false,
                                        null,
                                        creditorReferenceId
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
                        new it.pagopa.ecommerce.commons.domain.v2.PaymentNotice(
                                new PaymentToken(TransactionTestUtils.PAYMENT_TOKEN),
                                new RptId(TransactionTestUtils.RPT_ID),
                                new TransactionAmount(0),
                                new TransactionDescription("desc"),
                                new PaymentContextCode(TEST_CPP.toString()),
                                List.of(new PaymentTransferInfo("77777777777", false, MOCK_AMOUNT, null)),
                                false,
                                new CompanyName(null),
                                creditorReferenceId
                        )
                ),
                TransactionTestUtils.EMAIL,
                "faultCode",
                "faultCodeString",
                Transaction.ClientId.WISP_REDIRECT,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new EmptyTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
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
                                UUID.randomUUID(),
                                new TransactionId(transactionActivatedEvent.getTransactionId()),
                                UUID.randomUUID()
                        )
                )
                .expectNextMatches(
                        res -> res.getPayments().get(0).getRptId()
                                .equals(transactionRequestDto.getPaymentNotices().get(0).getRptId())
                                && res.getPayments().get(0).getCreditorReferenceId().equals(creditorReferenceId)
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
    void shouldNewTransactionTransactionActivatedForCheckoutCart() {
        ClientIdDto clientIdDto = ClientIdDto.CHECKOUT_CART;
        final var creditorReferenceId = UUID.randomUUID().toString();
        UUID TEST_SESSION_TOKEN = UUID.randomUUID();
        UUID TEST_CPP = UUID.randomUUID();
        UUID TRANSACTION_ID = UUID.randomUUID();

        NewTransactionRequestDto transactionRequestDto = new NewTransactionRequestDto()
                .email(EMAIL_STRING)
                .addPaymentNoticesItem(
                        new PaymentNoticeInfoDto().rptId(TransactionTestUtils.RPT_ID).amount(MOCK_AMOUNT)
                );

        TransactionActivatedData transactionActivatedData = new TransactionActivatedData();
        transactionActivatedData.setEmail(TransactionTestUtils.EMAIL);
        transactionActivatedData
                .setPaymentNotices(
                        List.of(
                                new PaymentNotice(
                                        TransactionTestUtils.PAYMENT_TOKEN,
                                        null,
                                        "dest",
                                        0L,
                                        TEST_CPP.toString(),
                                        List.of(new PaymentTransferInformation("77777777777", false, 0L, null)),
                                        false,
                                        null,
                                        creditorReferenceId
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
                        new it.pagopa.ecommerce.commons.domain.v2.PaymentNotice(
                                new PaymentToken(TransactionTestUtils.PAYMENT_TOKEN),
                                new RptId(TransactionTestUtils.RPT_ID),
                                new TransactionAmount(0),
                                new TransactionDescription("desc"),
                                new PaymentContextCode(TEST_CPP.toString()),
                                List.of(new PaymentTransferInfo("77777777777", false, MOCK_AMOUNT, null)),
                                false,
                                new CompanyName(null),
                                creditorReferenceId
                        )
                ),
                TransactionTestUtils.EMAIL,
                "faultCode",
                "faultCodeString",
                Transaction.ClientId.CHECKOUT_CART,
                "idCart",
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC,
                new EmptyTransactionGatewayActivationData(),
                TransactionTestUtils.USER_ID
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
                                UUID.randomUUID(),
                                new TransactionId(transactionActivatedEvent.getTransactionId()),
                                UUID.randomUUID()
                        )
                )
                .expectNextMatches(
                        res -> res.getPayments().get(0).getRptId()
                                .equals(transactionRequestDto.getPaymentNotices().get(0).getRptId())
                                && res.getPayments().get(0).getCreditorReferenceId() == null
                                && res.getIdCart().equals("idCart")
                                && res.getStatus().equals(TransactionStatusDto.ACTIVATED)
                                && res.getClientId()
                                        .equals(NewTransactionResponseDto.ClientIdEnum.valueOf(clientIdDto.getValue()))
                                && !res.getTransactionId().isEmpty()
                                && !res.getAuthToken().isEmpty()
                )
                .verifyComplete();
    }

    static Stream<Arguments> clientIdMapping() {
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
    @MethodSource("clientIdMapping")
    void shouldConvertClientIdSuccessfully(
                                           String expected,
                                           Transaction.ClientId clientId
    ) {
        assertEquals(expected, transactionsService.convertClientId(clientId).toString());
    }

    @Test
    void shouldRejectNullClientId() {
        assertThrows(
                InvalidRequestException.class,
                () -> transactionsService.convertClientId(null)
        );
    }

    @Test
    void shouldReturnTransactionInfo() {
        // pre-conditions
        ClosureErrorData closureErrorData = new ClosureErrorData();
        closureErrorData.setErrorDescription("errorDescription");
        closureErrorData.setErrorType(ClosureErrorData.ErrorType.KO_RESPONSE_RECEIVED);
        closureErrorData.setHttpErrorCode(HttpStatus.BAD_REQUEST);
        Transaction transaction = TransactionTestUtils.transactionDocument(
                it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.CLOSED,
                ZonedDateTime.now()
        );
        transaction.setGatewayAuthorizationStatus("gatewayAuthorizationStatus");
        transaction.setAuthorizationErrorCode("authorizationErrorCode");
        transaction.setPaymentGateway("NPG");
        transaction.setAuthorizationCode("authorizationCode");
        transaction.setSendPaymentResultOutcome(TransactionUserReceiptData.Outcome.KO);
        transaction.setClosureErrorData(closureErrorData);
        transaction.setFeeTotal(200);
        String transactionId = transaction.getTransactionId();
        String userId = transaction.getUserId();
        TransactionInfoDto expected = new TransactionInfoDto()
                .transactionId(transactionId)
                .addPaymentsItem(
                        new PaymentInfoDto()
                                .paymentToken(TransactionTestUtils.PAYMENT_TOKEN)
                                .rptId(TransactionTestUtils.RPT_ID)
                                .reason(TransactionTestUtils.DESCRIPTION)
                                .amount(TransactionTestUtils.AMOUNT)
                                .addTransferListItem(
                                        new TransferDto()
                                                .paFiscalCode(TransactionTestUtils.TRANSFER_PA_FISCAL_CODE)
                                                .digitalStamp(TransactionTestUtils.TRANSFER_DIGITAL_STAMP)
                                                .transferCategory(TransactionTestUtils.TRANSFER_CATEGORY)
                                                .transferAmount(TransactionTestUtils.TRANSFER_AMOUNT)
                                )
                                .isAllCCP(TransactionTestUtils.IS_ALL_CCP_FALSE)
                                .creditorReferenceId(null)
                )
                .status(TransactionStatusDto.CLOSED)
                .idCart(TransactionTestUtils.ID_CART)
                .clientId(TransactionInfoDto.ClientIdEnum.CHECKOUT)
                .feeTotal(200)
                .nodeInfo(
                        new TransactionInfoNodeInfoDto()
                                .sendPaymentResultOutcome(TransactionInfoNodeInfoDto.SendPaymentResultOutcomeEnum.KO)
                                .closePaymentResultError(
                                        new TransactionInfoNodeInfoClosePaymentResultErrorDto()
                                                .statusCode(BigDecimal.valueOf(400))
                                                .description("errorDescription")
                                )
                )
                .gatewayInfo(
                        new TransactionInfoGatewayInfoDto()
                                .gateway("NPG")
                                .authorizationStatus("gatewayAuthorizationStatus")
                                .authorizationCode("authorizationCode")
                                .errorCode("authorizationErrorCode")
                );
        Mockito.when(transactionsViewRepository.findById(any(String.class))).thenReturn(Mono.just(transaction));
        Mockito.when(transactionsUtils.convertEnumerationV2(any())).thenCallRealMethod();

        // test
        StepVerifier
                .create(transactionsService.getTransactionInfo(transactionId, UUID.fromString(userId)))
                .assertNext(
                        transactionInfo -> assertEquals(expected, transactionInfo)
                )
                .verifyComplete();
    }

    static Stream<BaseTransactionView> invalidTransactionVersionMethodSource() {
        return Stream.of(
                // transaction v1
                it.pagopa.ecommerce.commons.v1.TransactionTestUtils.transactionDocument(
                        it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto.ACTIVATED,
                        ZonedDateTime.now()
                ),
                // mocked base transaction (class different to any known transaction version)
                Mockito.mock(BaseTransactionView.class)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidTransactionVersionMethodSource")
    void shouldReturnErrorForGetTransactionInfoOnUnmanagedTransactionVersion(
                                                                             BaseTransactionView invalidBaseTransaction
    ) {
        // pre-conditions
        ClosureErrorData closureErrorData = new ClosureErrorData();
        closureErrorData.setErrorDescription("errorDescription");
        closureErrorData.setErrorType(ClosureErrorData.ErrorType.KO_RESPONSE_RECEIVED);
        closureErrorData.setHttpErrorCode(HttpStatus.BAD_REQUEST);
        Mockito.when(transactionsViewRepository.findById(any(String.class)))
                .thenReturn(Mono.just(invalidBaseTransaction));
        Mockito.when(transactionsUtils.convertEnumerationV2(any())).thenCallRealMethod();
        String transactionId = TransactionTestUtils.TRANSACTION_ID;
        // test
        StepVerifier
                .create(transactionsService.getTransactionInfo(transactionId, null))
                .expectError(NotImplementedException.class)
                .verify();
    }
}
