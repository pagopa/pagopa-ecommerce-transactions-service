package it.pagopa.transactions.projections.handlers.v2;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.commands.data.AuthorizationRequestedEventData;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import java.time.ZoneId;
import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.ZonedDateTime;
import java.util.*;

@ExtendWith(MockitoExtension.class)
class AuthorizationRequestProjectionHandlerTest {

    private it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler authorizationRequestProjectionHandler;

    @Mock
    private TransactionsViewRepository transactionsViewRepository;

    @Test
    void shouldUpdateTransactionWithAuthorizationRequestedStatus() {
        Transaction initialDocument = TransactionTestUtils.transactionDocument(
                TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );

        authorizationRequestProjectionHandler = new AuthorizationRequestProjectionHandler(
                transactionsViewRepository,
                true
        );

        Integer fee = 50;
        ZonedDateTime fixedEventTime = ZonedDateTime.of(2025, 7, 25, 14, 47, 31, 0, ZoneId.of("Europe/Rome"));

        AuthorizationRequestedEventData authorizationData = new AuthorizationRequestedEventData(
                new AuthorizationRequestData(
                        new TransactionId(initialDocument.getTransactionId()),
                        null,
                        initialDocument.getEmail(),
                        fee,
                        null,
                        TransactionTestUtils.PSP_ID,
                        TransactionTestUtils.PAYMENT_TYPE_CODE,
                        "brokerName",
                        "pspChannelCode",
                        "CARDS",
                        "paymentMethodDescription",
                        "pspBusinessName",
                        false,
                        initialDocument.getPaymentGateway(),
                        Optional.of(UUID.randomUUID().toString()),
                        Optional.empty(),
                        "VISA",
                        null,
                        "http://asset",
                        Optional.of(Map.of("VISA", "http://visaAsset")),
                        UUID.randomUUID().toString()
                ),
                TransactionTestUtils.transactionAuthorizationRequestedEvent()
        );

        Transaction expectedDocument = new Transaction(
                initialDocument.getTransactionId(),
                initialDocument.getPaymentNotices(),
                fee,
                initialDocument.getEmail(),
                TransactionStatusDto.AUTHORIZATION_REQUESTED,
                initialDocument.getClientId(),
                initialDocument.getCreationDate(),
                initialDocument.getIdCart(),
                initialDocument.getRrn(),
                initialDocument.getUserId(),
                TransactionTestUtils.PAYMENT_TYPE_CODE,
                TransactionTestUtils.PSP_ID,
                fixedEventTime.toInstant().toEpochMilli()
        );

        when(transactionsViewRepository.findById(initialDocument.getTransactionId()))
                .thenReturn(Mono.just(initialDocument));

        when(transactionsViewRepository.save(any(Transaction.class)))
                .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

        StepVerifier.create(authorizationRequestProjectionHandler.handle(authorizationData))
                .assertNext(result -> {
                    assertThat(result.getTransactionId()).isEqualTo(expectedDocument.getTransactionId());
                    assertThat(result.getStatus()).isEqualTo(TransactionStatusDto.AUTHORIZATION_REQUESTED);
                    assertThat(result.getFeeTotal()).isEqualTo(fee);
                    assertThat(result.getPaymentTypeCode()).isEqualTo(TransactionTestUtils.PAYMENT_TYPE_CODE);
                    assertThat(result.getPspId()).isEqualTo(TransactionTestUtils.PSP_ID);
                    assertThat(result.getLastProcessedEventAt()).isNotNull();
                })
                .verifyComplete();

        verify(transactionsViewRepository).save(any(Transaction.class));

    }

    @Test
    void shouldReturnUpdatedTransactionWithoutSavingWhenUpdateDisabled() {
        Transaction initialDocument = TransactionTestUtils.transactionDocument(
                TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );
        Integer fee = 50;
        ZonedDateTime fixedEventTime = ZonedDateTime.of(2025, 7, 25, 14, 47, 31, 0, ZoneId.of("Europe/Rome"));

        AuthorizationRequestedEventData authorizationData = new AuthorizationRequestedEventData(
                new AuthorizationRequestData(
                        new TransactionId(initialDocument.getTransactionId()),
                        null,
                        initialDocument.getEmail(),
                        fee,
                        null,
                        TransactionTestUtils.PSP_ID,
                        TransactionTestUtils.PAYMENT_TYPE_CODE,
                        "brokerName",
                        "pspChannelCode",
                        "CARDS",
                        "paymentMethodDescription",
                        "pspBusinessName",
                        false,
                        initialDocument.getPaymentGateway(),
                        Optional.of(UUID.randomUUID().toString()),
                        Optional.empty(),
                        "VISA",
                        null,
                        "http://asset",
                        Optional.of(Map.of("VISA", "http://visaAsset")),
                        UUID.randomUUID().toString()
                ),
                TransactionTestUtils.transactionAuthorizationRequestedEvent()
        );

        authorizationRequestProjectionHandler = new AuthorizationRequestProjectionHandler(
                transactionsViewRepository,
                false
        );

        StepVerifier.create(authorizationRequestProjectionHandler.handle(authorizationData))
                .verifyComplete();

        verify(transactionsViewRepository, never()).save(any());
        verify(transactionsViewRepository, never()).findByTransactionId(any());
    }

}
