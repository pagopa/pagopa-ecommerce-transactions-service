package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentRequestDto;
import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.notifications.templates.success.*;
import it.pagopa.generated.notifications.v1.dto.NotificationEmailResponseDto;
import it.pagopa.generated.transactions.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.NotificationsServiceClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
import it.pagopa.transactions.documents.TransactionAuthorizationRequestData;
import it.pagopa.transactions.documents.TransactionClosureSendData;
import it.pagopa.transactions.documents.TransactionClosureSentEvent;
import it.pagopa.transactions.documents.TransactionEvent;
import it.pagopa.transactions.domain.EmptyTransaction;
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.domain.TransactionActivated;
import it.pagopa.transactions.domain.TransactionWithCompletedAuthorization;
import it.pagopa.transactions.domain.pojos.BaseTransaction;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionEventCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
@Slf4j
public class TransactionSendClosureHandler implements CommandHandler<TransactionClosureSendCommand, Mono<TransactionClosureSentEvent>> {

    @Autowired
    NodeForPspClient nodeForPspClient;

    @Autowired
    private TransactionsEventStoreRepository<TransactionClosureSendData> transactionEventStoreRepository;

    @Autowired
    private TransactionsEventStoreRepository<TransactionAuthorizationRequestData> authorizationRequestedEventStoreRepository;

    @Autowired
    private TransactionsEventStoreRepository<Object> eventStoreRepository;

    @Autowired
    NotificationsServiceClient notificationsServiceClient;

    @Override
    public Mono<TransactionClosureSentEvent> handle(TransactionClosureSendCommand command) {
        Mono<Transaction> transaction = replayTransactionEvents(command.getData().transaction().getTransactionId().value());
        // TransactionActivated transaction = command.getData().transaction();

        Mono<? extends BaseTransaction> alreadyProcessedError = transaction
                .cast(BaseTransaction.class)
                .doOnNext(t -> log.error("Error: requesting closure status update for transaction in state {}", t.getStatus()))
                .flatMap(t -> Mono.error(new AlreadyProcessedException(t.getRptId())));

        return transaction
                .cast(BaseTransaction.class)
                .filter(t -> t.getStatus() == TransactionStatusDto.AUTHORIZED)
                .switchIfEmpty(alreadyProcessedError)
                .cast(TransactionWithCompletedAuthorization.class)
                .flatMap(tx -> {
                    UpdateAuthorizationRequestDto updateAuthorizationRequest = command.getData().updateAuthorizationRequest();
                    return authorizationRequestedEventStoreRepository.findByTransactionIdAndEventCode(
                                    tx.getTransactionId().value().toString(),
                                    TransactionEventCode.TRANSACTION_AUTHORIZATION_REQUESTED_EVENT
                            )
                            .switchIfEmpty(Mono.error(new TransactionNotFoundException(tx.getTransactionActivatedData().getPaymentToken())))
                            .flatMap(authorizationRequestedEvent -> {
                                TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

                        ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                                .paymentTokens(List.of(tx.getTransactionActivatedData().getPaymentToken()))
                                .outcome(authorizationResultToOutcomeV2(updateAuthorizationRequest.getAuthorizationResult()))
                                .idPSP(authorizationRequestData.getPspId())
                                .idBrokerPSP(authorizationRequestData.getBrokerName())
                                .idChannel(authorizationRequestData.getPspChannelCode())
                                .transactionId(tx.getTransactionId().value().toString())
                                .totalAmount(new BigDecimal(tx.getAmount().value() + authorizationRequestData.getFee()))
                                .fee(new BigDecimal(authorizationRequestData.getFee()))
                                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                                .paymentMethod(authorizationRequestData.getPaymentTypeCode())
                                .additionalPaymentInformations(
                                        Map.of(
                                                "outcome_payment_gateway", updateAuthorizationRequest.getAuthorizationResult().toString(),
                                                "authorization_code", updateAuthorizationRequest.getAuthorizationCode()
                                        )
                                );

                        return nodeForPspClient.closePaymentV2(closePaymentRequest);
                    })
                    .flatMap(response -> {
                        TransactionStatusDto newStatus;

                        switch (response.getOutcome()) {
                            case OK -> newStatus = TransactionStatusDto.CLOSED;
                            case KO -> newStatus = TransactionStatusDto.CLOSURE_FAILED;
                            default -> {
                                return Mono.error(new RuntimeException("Invalid outcome result enum value"));
                            }
                        }

                        TransactionClosureSendData closureSendData =
                                new TransactionClosureSendData(
                                        response.getOutcome(),
                                        newStatus
                                );

                        TransactionClosureSentEvent event = new TransactionClosureSentEvent(
                                tx.getTransactionId().value().toString(),
                                tx.getRptId().value(),
                                tx.getTransactionActivatedData().getPaymentToken(),
                                closureSendData
                        );

                                Mono<NotificationEmailResponseDto> emailResponse = notificationsServiceClient.sendSuccessEmail(
                                        new NotificationsServiceClient.SuccessTemplateRequest(
                                                tx.getEmail().value(),
                                                "Hai pagato un avviso di pagamento PagoPA",
                                                "it-IT",
                                                new SuccessTemplate(
                                                        new TransactionTemplate(
                                                                tx.getTransactionId().value().toString(),
                                                                tx.getCreationDate().toString(),
                                                                amountToHumanReadableString(tx.getAmount().value()),
                                                                new PspTemplate(
                                                                        tx.getTransactionAuthorizationRequestData().getPspId(),
                                                                        new FeeTemplate(amountToHumanReadableString(tx.getTransactionAuthorizationRequestData().getFee()))
                                                                ),
                                                                "RRN",
                                                                "authorizationCode",
                                                                new PaymentMethodTemplate(
                                                                        tx.getTransactionAuthorizationRequestData().getPaymentInstrumentId(),
                                                                        "paymentMethodLogo", // TODO: Logos
                                                                        null,
                                                                        false
                                                                )
                                                        ),
                                                        new UserTemplate(
                                                                new DataTemplate(
                                                                        null,
                                                                        null,
                                                                        null
                                                                ),
                                                                tx.getEmail().value()
                                                        ),
                                                        new CartTemplate(
                                                                List.of(
                                                                        new ItemTemplate(
                                                                                new RefNumberTemplate(
                                                                                        RefNumberTemplate.Type.CODICE_AVVISO,
                                                                                        tx.getRptId().value()
                                                                                ),
                                                                                new DebtorTemplate(
                                                                                        null,
                                                                                        null
                                                                                ),
                                                                                new PayeeTemplate(
                                                                                        null,
                                                                                        null
                                                                                ),
                                                                                tx.getDescription().value(),
                                                                                amountToHumanReadableString(tx.getAmount().value())
                                                                        )
                                                                ),
                                                                amountToHumanReadableString(tx.getAmount().value())
                                                        )
                                                )
                                        )
                                );

                                return emailResponse.flatMap(v -> transactionEventStoreRepository.save(event));
                            });
                });
    }

    private ClosePaymentRequestDto.OutcomeEnum authorizationResultToOutcome(AuthorizationResultDto authorizationResult) {
        switch (authorizationResult) {
            case OK -> {
                return ClosePaymentRequestDto.OutcomeEnum.OK;
            }
            case KO -> {
                return ClosePaymentRequestDto.OutcomeEnum.KO;
            }
            default ->
                    throw new RuntimeException("Missing authorization result enum value mapping to Nodo closePayment outcome");
        }
    }

    private ClosePaymentRequestV2Dto.OutcomeEnum authorizationResultToOutcomeV2(AuthorizationResultDto authorizationResult) {
        switch (authorizationResult) {
            case OK -> {
                return ClosePaymentRequestV2Dto.OutcomeEnum.OK;
            }
            case KO -> {
                return ClosePaymentRequestV2Dto.OutcomeEnum.KO;
            }
            default ->
                    throw new RuntimeException("Missing authorization result enum value mapping to Nodo closePaymentV2 outcome");
        }
    }

    private Mono<Transaction> replayTransactionEvents(UUID transactionId) {
        Flux<TransactionEvent<Object>> events = eventStoreRepository.findByTransactionId(transactionId);

        return events.reduce(new EmptyTransaction(), Transaction::applyEvent);
    }

    private String amountToHumanReadableString(int amount) {
        return "€ %s".formatted(amount / 100);
    }
}
