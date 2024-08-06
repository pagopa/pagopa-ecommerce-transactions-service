package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.documents.BaseTransactionView;
import it.pagopa.ecommerce.commons.documents.v1.Transaction;
import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.Email;
import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.domain.v1.TransactionWithRequestedAuthorization;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.PaymentNoticeInfoDto;
import it.pagopa.transactions.exceptions.NotImplementedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

class TransactionsUtilsTest {

    private TransactionsEventStoreRepository<Object> eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private TransactionsUtils transactionsUtils = new TransactionsUtils(eventStoreRepository, "3020");

    @Test
    void shouldReduceTransactionCorrectly() {
        TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);
        TransactionActivatedEvent transactionActivatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent transactionAuthorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();
        Flux events = Flux.just(transactionActivatedEvent, transactionAuthorizationRequestedEvent);
        given(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
                .willReturn(events);
        StepVerifier.create(transactionsUtils.reduceEventsV1(transactionId))
                .expectNextMatches(
                        baseTransaction -> baseTransaction instanceof TransactionWithRequestedAuthorization
                                && baseTransaction.getStatus() == TransactionStatusDto.AUTHORIZATION_REQUESTED
                )
                .verifyComplete();
    }

    @Test
    void shouldThrowTransactionNotFoundForNoEventsFoundForTransactionId() {
        TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);
        given(eventStoreRepository.findByTransactionIdOrderByCreationDateAsc(transactionId.value().toString()))
                .willReturn(Flux.empty());
        StepVerifier.create(transactionsUtils.reduceEventsV1(transactionId))
                .expectErrorMatches(
                        ex -> ex instanceof TransactionNotFoundException transactionNotFoundException
                                && transactionNotFoundException.getPaymentToken()
                                        .equals(transactionId.value().toString())
                )
                .verify();
    }

    @Test
    void shouldConvertAllCommonsStatusCorrectly() {
        for (TransactionStatusDto status : TransactionStatusDto.values()) {
            assertEquals(status.toString(), transactionsUtils.convertEnumerationV1(status).toString());
        }
    }

    @Test
    void shouldCreateWarmupRequestCorrectlyForEmptyNoticeCodePrefix() {
        TransactionsUtils utils = new TransactionsUtils(null, "");
        NewTransactionRequestDto warmupRequest = utils.buildWarmupRequestV1();
        for (PaymentNoticeInfoDto p : warmupRequest.getPaymentNotices()) {
            assertNotNull(p.getRptId());
            assertDoesNotThrow(() -> new RptId(p.getRptId()));
        }
    }

    @Test
    void shouldCreateWarmupRequestCorrectlyForValuedNoticeCodePrefix() {
        TransactionsUtils utils = new TransactionsUtils(null, "3020");
        NewTransactionRequestDto warmupRequest = utils.buildWarmupRequestV1();
        for (PaymentNoticeInfoDto p : warmupRequest.getPaymentNotices()) {
            assertNotNull(p.getRptId());
            assertDoesNotThrow(() -> new RptId(p.getRptId()));
            assertEquals("3020", new RptId(p.getRptId()).getNoticeId().substring(0, 4));
        }

    }

    @Test
    void shouldCreateWarmupRequestCorrectlyForValuedNoticeCodePrefixLongerThanNoticeCodeLength() {
        String noticeCode = new RptId(TransactionTestUtils.RPT_ID).getNoticeId();
        TransactionsUtils utils = new TransactionsUtils(null, noticeCode.concat("BBB"));
        NewTransactionRequestDto warmupRequest = utils.buildWarmupRequestV1();
        for (PaymentNoticeInfoDto p : warmupRequest.getPaymentNotices()) {
            assertNotNull(p.getRptId());
            assertDoesNotThrow(() -> new RptId(p.getRptId()));
            assertEquals(noticeCode, new RptId(p.getRptId()).getNoticeId());
        }

    }

    @Test
    void shouldGetPaymentNoticesFromTransactionV1() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        assertNotNull(
                utils.getPaymentNotices(
                        it.pagopa.ecommerce.commons.v1.TransactionTestUtils
                                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now())
                )
        );
    }

    @Test
    void shouldGetPaymentNoticesFromTransactionV2() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        assertNotNull(
                utils.getPaymentNotices(
                        it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now())
                )
        );
    }

    @Test
    void shouldGetPaymentNoticesFromTransactionInvalidClass() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        assertThrows(
                NotImplementedException.class,
                () -> utils.getPaymentNotices(Mockito.mock(BaseTransactionView.class))
        );
    }

    @Test
    void shouldGetClientIdFromTransactionV1() {
        it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId clientId = Transaction.ClientId.CHECKOUT;
        TransactionsUtils utils = new TransactionsUtils(null, null);
        it.pagopa.ecommerce.commons.documents.v1.Transaction transaction = it.pagopa.ecommerce.commons.v1.TransactionTestUtils
                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now());
        transaction.setClientId(clientId);
        String result = utils.getClientId(transaction);
        assertNotNull(result);
        assertEquals(clientId.name(), result);
    }

    @Test
    void shouldGetClientIdFromTransactionV2() {
        it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId clientId = it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT;
        TransactionsUtils utils = new TransactionsUtils(null, null);
        it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now());
        transaction.setClientId(clientId);
        String result = utils.getClientId(transaction);
        assertNotNull(result);
        assertEquals(clientId.name(), result);
    }

    @Test
    void shouldGetEffectiveClientIdFromTransactionV2() {
        it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId clientId = it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.WISP_REDIRECT;
        TransactionsUtils utils = new TransactionsUtils(null, null);
        it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now());
        transaction.setClientId(clientId);
        String result = utils.getEffectiveClientId(transaction);
        assertNotNull(result);
        assertEquals(clientId.getEffectiveClient().name(), result);
    }

    @Test
    void shouldGetClientIdFromTransactionInvalidClass() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        assertThrows(NotImplementedException.class, () -> utils.getClientId(Mockito.mock(BaseTransactionView.class)));
    }

    @Test
    void shouldGetEmailFromTransactionV1() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        it.pagopa.ecommerce.commons.documents.v1.Transaction transaction = it.pagopa.ecommerce.commons.v1.TransactionTestUtils
                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now());
        Confidential<Email> email = utils.getEmail(transaction);
        assertNotNull(email);
    }

    @Test
    void shouldGetEmailFromTransactionV2() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now());
        Confidential<Email> email = utils.getEmail(transaction);
        assertNotNull(email);
    }

    @Test
    void shouldGetEmailFromTransactionInvalidClass() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        assertThrows(NotImplementedException.class, () -> utils.getEmail(Mockito.mock(BaseTransactionView.class)));
    }

    @Test
    void shouldGetTransactionTotalAmountV1() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        it.pagopa.ecommerce.commons.documents.v1.Transaction transaction = it.pagopa.ecommerce.commons.v1.TransactionTestUtils
                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now());
        int totalAmount = transaction.getPaymentNotices().stream()
                .mapToInt(
                        it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount
                ).sum();
        Integer methodTotalAmount = utils.getTransactionTotalAmount(transaction);
        assertEquals(totalAmount, methodTotalAmount.intValue());
    }

    @Test
    void shouldGetTransactionTotalAmountV2() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now());
        int totalAmount = transaction.getPaymentNotices().stream()
                .mapToInt(
                        it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount
                ).sum();
        Integer methodTotalAmount = utils.getTransactionTotalAmount(transaction);
        assertEquals(totalAmount, methodTotalAmount.intValue());
    }

    @Test
    void shouldGetRptIdV1() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        it.pagopa.ecommerce.commons.documents.v1.Transaction transaction = it.pagopa.ecommerce.commons.v1.TransactionTestUtils
                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now());
        RptId rptId = new RptId(transaction.getPaymentNotices().get(0).getRptId());
        RptId rptIdExtracted = new RptId(utils.getRptId(transaction, 0));
        assertEquals(rptId, rptIdExtracted);
    }

    @Test
    void shouldGetRptIdV2() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now());
        RptId rptId = new RptId(transaction.getPaymentNotices().get(0).getRptId());
        RptId rptIdExtracted = new RptId(utils.getRptId(transaction, 0));
        assertEquals(rptId, rptIdExtracted);
    }

    @Test
    void shouldGetIsAllCCPV1() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        it.pagopa.ecommerce.commons.documents.v1.Transaction transaction = it.pagopa.ecommerce.commons.v1.TransactionTestUtils
                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now());
        boolean isAllCcp = transaction.getPaymentNotices().get(0).isAllCCP();
        boolean isAllCcpCalculated = utils.isAllCcp(transaction, 0);
        assertEquals(isAllCcp, isAllCcpCalculated);
    }

    @Test
    void shouldGetIsAllCCPV2() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        it.pagopa.ecommerce.commons.documents.v2.Transaction transaction = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionDocument(TransactionStatusDto.ACTIVATED, ZonedDateTime.now());
        boolean isAllCcp = transaction.getPaymentNotices().get(0).isAllCCP();
        boolean isAllCcpCalculated = utils.isAllCcp(transaction, 0);
        assertEquals(isAllCcp, isAllCcpCalculated);
    }

}
