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

    private final TransactionsUtils transactionsUtils = Mockito.mock(TransactionsUtils.class);

    @Autowired
    private final TransactionsService transactionsService = new TransactionsService(
            transactionsUtils,
            transactionsViewRepository
    );

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
