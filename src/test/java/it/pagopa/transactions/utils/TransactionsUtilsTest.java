package it.pagopa.transactions.utils;

import it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.domain.Confidential;
import it.pagopa.ecommerce.commons.domain.v2.*;
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

import java.time.ZonedDateTime;
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

        TransactionActivated transactionActivated = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivated(ZonedDateTime.now().toString());

        assertNotNull(
                utils.getPaymentNotices(transactionActivated)
        );
    }

    @Test
    void shouldGetClientIdFromTransactionActivatedEventV2() {
        it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId clientId = it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.CHECKOUT;
        TransactionsUtils utils = new TransactionsUtils(null, null);
        TransactionActivated transactionActivatedCopy = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivated(ZonedDateTime.now().toString());
        TransactionActivated transactionActivated = new TransactionActivated(
                new TransactionId(it.pagopa.ecommerce.commons.v2.TransactionTestUtils.TRANSACTION_ID),
                transactionActivatedCopy.getPaymentNotices(),
                transactionActivatedCopy.getEmail(),
                null,
                null,
                clientId,
                null,
                150,
                it.pagopa.ecommerce.commons.v2.TransactionTestUtils.npgTransactionGatewayActivationData(),
                null
        );
        String result = utils.getClientId(transactionActivated);
        assertNotNull(result);
        assertEquals(clientId.name(), result);
    }

    @Test
    void shouldGetEffectiveClientIdFromTransactionActivatedEventV2() {
        it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId clientId = it.pagopa.ecommerce.commons.documents.v2.Transaction.ClientId.WISP_REDIRECT;
        TransactionsUtils utils = new TransactionsUtils(null, null);
        TransactionActivated transactionActivatedCopy = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivated(ZonedDateTime.now().toString());
        TransactionActivated transactionActivated = new TransactionActivated(
                new TransactionId(it.pagopa.ecommerce.commons.v2.TransactionTestUtils.TRANSACTION_ID),
                transactionActivatedCopy.getPaymentNotices(),
                transactionActivatedCopy.getEmail(),
                null,
                null,
                clientId,
                null,
                150,
                it.pagopa.ecommerce.commons.v2.TransactionTestUtils.npgTransactionGatewayActivationData(),
                null
        );
        String result = utils.getEffectiveClientId(transactionActivated);
        assertNotNull(result);
        assertEquals(clientId.getEffectiveClient().name(), result);
    }

    @Test
    void shouldGetEmailFromTransactionActivatedEventV2() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        TransactionActivated transactionActivated = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivated(ZonedDateTime.now().toString());
        Confidential<Email> email = utils.getEmail(transactionActivated);
        assertNotNull(email);
    }

    @Test
    void shouldGetTransactionTotalAmountV2() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        TransactionActivated transactionActivated = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivated(ZonedDateTime.now().toString());

        int totalAmount = transactionActivated.getPaymentNotices().stream()
                .mapToInt(
                        p -> p.transactionAmount().value()
                ).sum();
        Integer methodTotalAmount = utils.getTransactionTotalAmountFromEvent(transactionActivated);
        assertEquals(totalAmount, methodTotalAmount.intValue());
    }

    @Test
    void shouldGetRptIdV2() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        TransactionActivated transactionActivated = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivated(ZonedDateTime.now().toString());

        RptId rptId = transactionActivated.getPaymentNotices().getFirst().rptId();
        RptId rptIdExtracted = new RptId(utils.getRptId(transactionActivated, 0));
        assertEquals(rptId, rptIdExtracted);
    }

    @Test
    void shouldGetAllRptIdsV2() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        TransactionActivated transactionActivated = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivated(ZonedDateTime.now().toString());

        List<RptId> rptIdsFromData = transactionActivated.getPaymentNotices().stream()
                .map(PaymentNotice::rptId).toList();
        List<RptId> rptIdExtracted = utils.getRptIds(transactionActivated).stream().map(RptId::new).toList();
        assertEquals(rptIdsFromData, rptIdExtracted);
    }

    @Test
    void shouldGetIsAllCCPV2() {
        TransactionsUtils utils = new TransactionsUtils(null, null);
        TransactionActivated transactionActivated = it.pagopa.ecommerce.commons.v2.TransactionTestUtils
                .transactionActivated(ZonedDateTime.now().toString());
        boolean isAllCcp = transactionActivated.getPaymentNotices().getFirst().isAllCCP();
        boolean isAllCcpCalculated = utils.isAllCcp(transactionActivated, 0);
        assertEquals(isAllCcp, isAllCcpCalculated);
    }

}
