package it.pagopa.transactions.services.v2;

import it.pagopa.ecommerce.commons.documents.BaseTransactionView;
import it.pagopa.ecommerce.commons.documents.v2.*;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.v2.*;
import it.pagopa.ecommerce.commons.utils.UpdateTransactionStatusTracerUtils;
import it.pagopa.generated.transactions.v2.server.model.*;
import it.pagopa.transactions.commands.handlers.v2.TransactionSendClosureRequestHandler;
import it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler;
import it.pagopa.transactions.projections.handlers.v2.ClosureRequestedProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionsUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.redis.AutoConfigureDataRedis;

import java.time.ZonedDateTime;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@AutoConfigureDataRedis
class TransactionServiceTest {
    private final TransactionsUtils transactionsUtils = Mockito.mock(TransactionsUtils.class);
    private final TransactionsEventStoreRepository<Object> transactionsEventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);
    private final UpdateTransactionStatusTracerUtils updateTransactionStatusTracerUtils = Mockito
            .mock(UpdateTransactionStatusTracerUtils.class);
    private final TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandler = Mockito
            .mock(TransactionUpdateAuthorizationHandler.class);
    private final AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandler = Mockito
            .mock(AuthorizationUpdateProjectionHandler.class);
    private final TransactionSendClosureRequestHandler transactionSendClosureRequestHandler = Mockito
            .mock(TransactionSendClosureRequestHandler.class);
    private final ClosureRequestedProjectionHandler closureRequestedProjectionHandler = Mockito
            .mock(ClosureRequestedProjectionHandler.class);

    @Autowired
    private final TransactionsService transactionsService = new TransactionsService(
            transactionsUtils,
            transactionsEventStoreRepository,
            updateTransactionStatusTracerUtils,
            transactionUpdateAuthorizationHandler,
            authorizationUpdateProjectionHandler,
            transactionSendClosureRequestHandler,
            closureRequestedProjectionHandler
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
}
