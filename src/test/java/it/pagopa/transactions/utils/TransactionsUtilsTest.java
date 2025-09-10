package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v2.Email;
import it.pagopa.ecommerce.commons.domain.v2.RptId;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.domain.v1.TransactionWithRequestedAuthorization;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.generated.transactions.server.model.PaymentNoticeInfoDto;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

class TransactionsUtilsTest {

    private TransactionsEventStoreRepository<Object> eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private TransactionsUtils transactionsUtils = new TransactionsUtils(eventStoreRepository, "3020");

    @Test
    void shouldReduceTransactionCorrectly() {
        TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);
        it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent transactionActivatedEvent = it.pagopa.ecommerce.commons.v1.TransactionTestUtils
                .transactionActivateEvent();
        it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestedEvent transactionAuthorizationRequestedEvent = it.pagopa.ecommerce.commons.v1.TransactionTestUtils
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
    void shouldGetPaymentNoticesFromTransactionV2() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        assertNotNull(
                utils.getPaymentNotices(it.pagopa.ecommerce.commons.v2.TransactionTestUtils.transactionActivateEvent())
        );
    }

    @Test
    void shouldGetClientIdFromTransactionActivatedEventV2() {
        it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId clientId = it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT;
        TransactionsUtils utils = new TransactionsUtils(null, null);
        TransactionActivatedEvent transactionActivatedEvent = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivateEvent();
        transactionActivatedEvent.getData().setClientId(clientId);
        String result = utils.getClientId(transactionActivatedEvent);
        assertNotNull(result);
        assertEquals(clientId.name(), result);
    }

    @Test
    void shouldGetEffectiveClientIdFromTransactionActivatedEventV2() {
        it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId clientId = it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.WISP_REDIRECT;
        TransactionsUtils utils = new TransactionsUtils(null, null);
        TransactionActivatedEvent transactionActivatedEvent = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivateEvent();
        transactionActivatedEvent.getData().setClientId(clientId);
        String result = utils.getEffectiveClientId(transactionActivatedEvent);
        assertNotNull(result);
        assertEquals(clientId.getEffectiveClient().name(), result);
    }

    @Test
    void shouldGetEmailFromTransactionActivatedEventV2() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        TransactionActivatedEvent transactionActivateEvent = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivateEvent();
        Confidential<Email> email = utils.getEmail(transactionActivateEvent);
        assertNotNull(email);
    }

    @Test
    void shouldGetTransactionTotalAmountV2() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent transactionActivateEvent = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivateEvent();
        int totalAmount = transactionActivateEvent.getData().getPaymentNotices().stream()
                .mapToInt(
                        it.pagopa.ecommerce.commons.documents.PaymentNotice::getAmount
                ).sum();
        Integer methodTotalAmount = utils
                .getTransactionTotalAmountFromTransactionActivatedEvent(transactionActivateEvent);
        assertEquals(totalAmount, methodTotalAmount.intValue());
    }

    @Test
    void shouldGetRptIdV2() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent transactionActivateEvent = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivateEvent();
        RptId rptId = new RptId(transactionActivateEvent.getData().getPaymentNotices().getFirst().getRptId());
        RptId rptIdExtracted = new RptId(utils.getRptId(transactionActivateEvent, 0));
        assertEquals(rptId, rptIdExtracted);
    }

    @Test
    void shouldGetAllRptIdsV2() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent transactionActivateEvent = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivateEvent();
        List<RptId> rptIdsFromData = transactionActivateEvent.getData().getPaymentNotices().stream()
                .map(p -> new RptId(p.getRptId())).toList();
        List<RptId> rptIdExtracted = utils.getRptIds(transactionActivateEvent).stream().map(RptId::new).toList();
        assertEquals(rptIdsFromData, rptIdExtracted);
    }

    @Test
    void shouldGetIsAllCCPV2() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent transactionActivateEvent = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivateEvent();
        boolean isAllCcp = transactionActivateEvent.getData().getPaymentNotices().getFirst().isAllCCP();
        boolean isAllCcpCalculated = utils.isAllCcp(transactionActivateEvent, 0);
        assertEquals(isAllCcp, isAllCcpCalculated);
    }

}
