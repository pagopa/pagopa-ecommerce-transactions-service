package it.pagopa.transactions.commands.handlers.v1;

import it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedData;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedEvent;
import it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationRequestedEvent;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.v1.TransactionActivated;
import it.pagopa.ecommerce.commons.domain.v1.TransactionEventCode;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.v1.TransactionTestUtils;
import it.pagopa.generated.transactions.server.model.OutcomeXpayGatewayDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.AuthRequestDataUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class TransactionUpdateAuthorizationHandlerTest {

    private TransactionsEventStoreRepository<TransactionAuthorizationCompletedData> transactionEventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);

    private TransactionsEventStoreRepository<Object> eventStoreRepository = Mockito
            .mock(TransactionsEventStoreRepository.class);
    private TransactionId transactionId = new TransactionId(TransactionTestUtils.TRANSACTION_ID);

    private final UUIDUtils mockUuidUtils = Mockito.mock(UUIDUtils.class);

    private final TransactionsUtils transactionsUtils = new TransactionsUtils(
            eventStoreRepository,
            "warmUpNoticeCodePrefix"
    );

    private TransactionUpdateAuthorizationHandler updateAuthorizationHandler = new TransactionUpdateAuthorizationHandler(
            transactionEventStoreRepository,
            new AuthRequestDataUtils(mockUuidUtils),
            transactionsUtils
    );

    @Test
    void shouldSaveSuccessfulUpdate() {
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent event = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(AuthorizationResultDto.OK);
        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent, authorizationRequestedEvent);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction.getTransactionId(),
                transaction.getStatus().toString(),
                updateAuthorizationRequest,
                OffsetDateTime.now()
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                updateAuthorizationStatusData
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));
        Mockito.when(mockUuidUtils.uuidToBase64(transactionId.uuid()))
                .thenReturn(transactionId.uuid().toString());
        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(authorizationStatusUpdatedEvent -> authorizationStatusUpdatedEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1))
                .save(
                        argThat(
                                eventArg -> TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT.toString()
                                        .equals(eventArg.getEventCode())
                                        && eventArg.getData().getAuthorizationResultDto()
                                        .equals(AuthorizationResultDto.OK)
                        )
                );
    }

    @Test
    void shouldRejectTransactionInInvalidState() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Confidential<Email> email = TransactionTestUtils.EMAIL;
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";
        PaymentContextCode nullPaymentContextCode = new PaymentContextCode(null);
        String idCart = "idCart";

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                List.of(
                        new PaymentNotice(
                                paymentToken,
                                rptId,
                                amount,
                                description,
                                nullPaymentContextCode,
                                List.of(new PaymentTransferInfo(rptId.getFiscalCode(), false, amount.value(), null)),
                                false
                        )
                ),
                email,
                faultCode,
                faultCodeString,
                it.pagopa.ecommerce.commons.documents.v1.Transaction.ClientId.CHECKOUT,
                idCart,
                TransactionTestUtils.PAYMENT_TOKEN_VALIDITY_TIME_SEC
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.OK)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction.getTransactionId(),
                transaction.getStatus().toString(),
                updateAuthorizationRequest,
                OffsetDateTime.now()
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                updateAuthorizationStatusData
        );

        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldSetTransactionStatusToAuthorizationFailedOnGatewayKO() {
        TransactionActivatedEvent activatedEvent = TransactionTestUtils.transactionActivateEvent();
        TransactionAuthorizationRequestedEvent authorizationRequestedEvent = TransactionTestUtils
                .transactionAuthorizationRequestedEvent();

        TransactionAuthorizationCompletedEvent event = TransactionTestUtils
                .transactionAuthorizationCompletedEvent(AuthorizationResultDto.OK);
        BaseTransaction transaction = TransactionTestUtils.reduceEvents(activatedEvent, authorizationRequestedEvent);

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .outcomeGateway(
                        new OutcomeXpayGatewayDto()
                                .outcome(OutcomeXpayGatewayDto.OutcomeEnum.KO)
                                .authorizationCode("authorizationCode")
                )
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction.getTransactionId(),
                transaction.getStatus().toString(),
                updateAuthorizationRequest,
                OffsetDateTime.now()
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                updateAuthorizationStatusData
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));

        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(authorizationStatusUpdatedEvent -> authorizationStatusUpdatedEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(
                argThat(
                        eventArg -> TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT.toString()
                                .equals(eventArg.getEventCode())
                                && eventArg.getData().getAuthorizationResultDto().equals(AuthorizationResultDto.KO)
                )
        );
    }
}
