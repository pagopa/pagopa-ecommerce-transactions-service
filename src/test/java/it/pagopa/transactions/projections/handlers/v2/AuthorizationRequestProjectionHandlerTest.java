package it.pagopa.transactions.projections.handlers.v2;

import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.ecommerce.commons.v2.TransactionTestUtils;
import it.pagopa.transactions.commands.data.AuthorizationRequestData;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.ZonedDateTime;
import java.util.*;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationRequestProjectionHandlerTest {

    @InjectMocks
    private it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler authorizationRequestProjectionHandler;

    @Mock
    private TransactionsViewRepository transactionsViewRepository;

    @Test
    void shouldUpdateTransactionWithAuthorizationRequestedStatus() {
        Transaction initialDocument = TransactionTestUtils.transactionDocument(
                TransactionStatusDto.ACTIVATED,
                ZonedDateTime.now()
        );

        Integer fee = 50;

        AuthorizationRequestData authorizationData = new AuthorizationRequestData(
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
                TransactionTestUtils.PSP_ID
        );

        when(transactionsViewRepository.findById(initialDocument.getTransactionId()))
                .thenReturn(Mono.just(initialDocument));

        when(transactionsViewRepository.save(expectedDocument))
                .thenReturn(Mono.just(expectedDocument));

        StepVerifier.create(authorizationRequestProjectionHandler.handle(authorizationData))
                .expectNext(expectedDocument)
                .verifyComplete();

        verify(transactionsViewRepository).save(expectedDocument);
    }

}
