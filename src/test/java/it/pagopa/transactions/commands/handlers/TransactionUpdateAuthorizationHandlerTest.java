package it.pagopa.transactions.commands.handlers;

import it.pagopa.ecommerce.commons.documents.TransactionAuthorizationStatusUpdateData;
import it.pagopa.ecommerce.commons.documents.TransactionAuthorizationStatusUpdatedEvent;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.generated.server.model.AuthorizationResultDto;
import it.pagopa.ecommerce.commons.generated.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.commands.TransactionUpdateAuthorizationCommand;
import it.pagopa.transactions.commands.data.UpdateAuthorizationStatusData;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class TransactionUpdateAuthorizationHandlerTest {

    @InjectMocks
    private TransactionUpdateAuthorizationHandler updateAuthorizationHandler;
    @Mock
    private TransactionsEventStoreRepository<TransactionAuthorizationStatusUpdateData> transactionEventStoreRepository;

    private TransactionId transactionId = new TransactionId(UUID.randomUUID());

    @Test
    void shouldSaveSuccessfulUpdate() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                Arrays.asList(new NoticeCode(paymentToken,
                        rptId,
                        amount,
                        description)),
                email,
                faultCode,
                faultCodeString,
                TransactionStatusDto.AUTHORIZATION_REQUESTED
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction,
                updateAuthorizationRequest
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(transaction.getNoticeCodes().get(0).rptId(), updateAuthorizationStatusData);

        TransactionAuthorizationStatusUpdateData transactionAuthorizationStatusUpdateData = new TransactionAuthorizationStatusUpdateData(AuthorizationResultDto.KO, TransactionStatusDto.AUTHORIZED, "authorizationCode");

        TransactionAuthorizationStatusUpdatedEvent event = new TransactionAuthorizationStatusUpdatedEvent(
                transactionId.toString(),
                transaction.getNoticeCodes().stream().map(noticeCode -> new it.pagopa.ecommerce.commons.documents.NoticeCode(
                    transaction.getTransactionActivatedData().getNoticeCodes().stream().filter(noticeCodeData -> noticeCodeData.getRptId().equals(noticeCode.rptId().value())).findFirst().get().getPaymentToken(),
                    noticeCode.rptId().value(),
                    noticeCode.transactionDescription().value(),
                    noticeCode.transactionAmount().value()
                    )).toList(),
                transactionAuthorizationStatusUpdateData
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));

        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(authorizationStatusUpdatedEvent -> authorizationStatusUpdatedEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(argThat(eventArg -> eventArg.getData().getNewTransactionStatus().equals(TransactionStatusDto.AUTHORIZED)));
    }

    @Test
    void shouldRejectTransactionInInvalidState() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                Arrays.asList(new it.pagopa.ecommerce.commons.domain.NoticeCode(paymentToken,
                rptId,
                amount,
                description)),
                email,
                faultCode,
                faultCodeString,
                TransactionStatusDto.ACTIVATED
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.OK)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction,
                updateAuthorizationRequest
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(transaction.getNoticeCodes().get(0).rptId(), updateAuthorizationStatusData);

        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectErrorMatches(error -> error instanceof AlreadyProcessedException)
                .verify();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(0)).save(any());
    }

    @Test
    void shouldSetTransactionStatusToAuthorizationFailedOnGatewayKO() {
        PaymentToken paymentToken = new PaymentToken("paymentToken");
        RptId rptId = new RptId("77777777777111111111111111111");
        TransactionDescription description = new TransactionDescription("description");
        TransactionAmount amount = new TransactionAmount(100);
        Email email = new Email("foo@example.com");
        String faultCode = "faultCode";
        String faultCodeString = "faultCodeString";

        TransactionActivated transaction = new TransactionActivated(
                transactionId,
                Arrays.asList(new it.pagopa.ecommerce.commons.domain.NoticeCode(paymentToken,
                        rptId,
                        amount,
                        description)),
                email,
                faultCode,
                faultCodeString,
                TransactionStatusDto.AUTHORIZATION_REQUESTED
        );

        UpdateAuthorizationRequestDto updateAuthorizationRequest = new UpdateAuthorizationRequestDto()
                .authorizationResult(it.pagopa.generated.transactions.server.model.AuthorizationResultDto.KO)
                .authorizationCode("authorizationCode")
                .timestampOperation(OffsetDateTime.now());

        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction,
                updateAuthorizationRequest
        );

        TransactionUpdateAuthorizationCommand requestAuthorizationCommand = new TransactionUpdateAuthorizationCommand(transaction.getNoticeCodes().get(0).rptId(), updateAuthorizationStatusData);

        TransactionAuthorizationStatusUpdateData transactionAuthorizationStatusUpdateData = new TransactionAuthorizationStatusUpdateData(AuthorizationResultDto.KO, TransactionStatusDto.AUTHORIZATION_FAILED, "authorizationCode");

        TransactionAuthorizationStatusUpdatedEvent event = new TransactionAuthorizationStatusUpdatedEvent(
                transactionId.toString(),
                transaction.getNoticeCodes().stream().map(noticeCode -> new it.pagopa.ecommerce.commons.documents.NoticeCode(
                        transaction.getTransactionActivatedData().getNoticeCodes().stream().filter(noticeCode1 -> noticeCode1.getRptId().equals(noticeCode.rptId().value())).findFirst().get().getPaymentToken(),
                        noticeCode.rptId().value(),
                        noticeCode.transactionDescription().value(),
                        noticeCode.transactionAmount().value()
                )).toList(),
                transactionAuthorizationStatusUpdateData
        );

        /* preconditions */
        Mockito.when(transactionEventStoreRepository.save(any())).thenReturn(Mono.just(event));

        /* test */
        StepVerifier.create(updateAuthorizationHandler.handle(requestAuthorizationCommand))
                .expectNextMatches(authorizationStatusUpdatedEvent -> authorizationStatusUpdatedEvent.equals(event))
                .verifyComplete();

        Mockito.verify(transactionEventStoreRepository, Mockito.times(1)).save(argThat(eventArg -> eventArg.getData().getNewTransactionStatus().equals(TransactionStatusDto.AUTHORIZATION_FAILED)));
    }
}
