package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.nodo.v2.dto.ClosePaymentRequestV2Dto;
import it.pagopa.generated.notifications.templates.success.*;
import it.pagopa.generated.notifications.v1.dto.NotificationEmailResponseDto;
import it.pagopa.generated.transactions.server.model.AuthorizationResultDto;
import it.pagopa.generated.transactions.server.model.TransactionStatusDto;
import it.pagopa.generated.transactions.server.model.UpdateAuthorizationRequestDto;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.client.NotificationsServiceClient;
import it.pagopa.transactions.commands.TransactionClosureSendCommand;
import it.pagopa.transactions.documents.*;
import it.pagopa.transactions.domain.EmptyTransaction;
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.domain.TransactionWithCompletedAuthorization;
import it.pagopa.transactions.domain.pojos.BaseTransaction;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
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
                    TransactionAuthorizationRequestData transactionAuthorizationRequestData = tx.getTransactionAuthorizationRequestData();
                    TransactionAuthorizationStatusUpdateData transactionAuthorizationStatusUpdateData = tx.getTransactionAuthorizationStatusUpdateData();

                    ClosePaymentRequestV2Dto closePaymentRequest = new ClosePaymentRequestV2Dto()
                            .paymentTokens(List.of(tx.getTransactionActivatedData().getPaymentToken()))
                            .outcome(authorizationResultToOutcomeV2(transactionAuthorizationStatusUpdateData.getAuthorizationResult()))
                            .idPSP(transactionAuthorizationRequestData.getPspId())
                            .idBrokerPSP(transactionAuthorizationRequestData.getBrokerName())
                            .idChannel(transactionAuthorizationRequestData.getPspChannelCode())
                            .transactionId(tx.getTransactionId().value().toString())
                            .totalAmount(new BigDecimal(tx.getAmount().value() + transactionAuthorizationRequestData.getFee()))
                            .fee(new BigDecimal(transactionAuthorizationRequestData.getFee()))
                            .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                            .paymentMethod(transactionAuthorizationRequestData.getPaymentTypeCode())
                            .additionalPaymentInformations(
                                    Map.of(
                                            "outcome_payment_gateway", transactionAuthorizationStatusUpdateData.getAuthorizationResult().toString(),
                                            "authorization_code", updateAuthorizationRequest.getAuthorizationCode()
                                    )
                            );
                    return nodeForPspClient.closePaymentV2(closePaymentRequest)
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
                                String language = "it-IT"; // FIXME: Add language to AuthorizationRequestData

                                Mono<NotificationEmailResponseDto> emailResponse = notificationsServiceClient.sendSuccessEmail(
                                        new NotificationsServiceClient.SuccessTemplateRequest(
                                                tx.getEmail().value(),
                                                "Hai inviato un pagamento di %s € tramite pagoPA".formatted(amountToHumanReadableString(tx.getAmount().value())),
                                                language,
                                                new SuccessTemplate(
                                                        new TransactionTemplate(
                                                                tx.getTransactionId().value().toString(),
                                                                dateTimeToHumanReadableString(tx.getCreationDate(), Locale.forLanguageTag(language)), // FIXME: auth date, not tx creation date
                                                                amountToHumanReadableString(tx.getAmount().value()),
                                                                new PspTemplate(
                                                                        transactionAuthorizationRequestData.getPspId(),
                                                                        new FeeTemplate(amountToHumanReadableString(transactionAuthorizationRequestData.getFee()))
                                                                ),
                                                                "RRN",
                                                                updateAuthorizationRequest.getAuthorizationCode(),
                                                                new PaymentMethodTemplate(
                                                                        transactionAuthorizationRequestData.getPaymentInstrumentId(),
                                                                        "paymentMethodLogo", // TODO: Logos
                                                                        null,
                                                                        false
                                                                )
                                                        ),
                                                        new UserTemplate(
                                                                null,
                                                                tx.getEmail().value()
                                                        ),
                                                        new CartTemplate(
                                                                List.of(
                                                                        new ItemTemplate(
                                                                                new RefNumberTemplate(
                                                                                        RefNumberTemplate.Type.CODICE_AVVISO,
                                                                                        tx.getRptId().getNoticeId()
                                                                                ),
                                                                                null,
                                                                                new PayeeTemplate(
                                                                                        "payeeName",
                                                                                        tx.getRptId().getFiscalCode()
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

    private ClosePaymentRequestV2Dto.OutcomeEnum authorizationResultToOutcomeV2(AuthorizationResultDto authorizationResult) {
        switch (authorizationResult) {
            case OK -> {
                return ClosePaymentRequestV2Dto.OutcomeEnum.OK;
            }
            case KO -> {
                return ClosePaymentRequestV2Dto.OutcomeEnum.KO;
            }
            default ->
                    throw new IllegalArgumentException("Missing authorization result enum value mapping to Nodo closePaymentV2 outcome");
        }
    }

    private Mono<Transaction> replayTransactionEvents(UUID transactionId) {
        Flux<TransactionEvent<Object>> events = eventStoreRepository.findByTransactionId(transactionId.toString());

        return events.reduce(new EmptyTransaction(), Transaction::applyEvent);
    }

    private String amountToHumanReadableString(int amount) {
        String repr = String.valueOf(amount);
        int centsSeparationIndex = Math.max(0, repr.length() - 2);

        String cents = repr.substring(centsSeparationIndex);
        String euros = repr.substring(0, centsSeparationIndex);

        if (euros.isEmpty()) {
            euros = "0";
        }

        if (cents.length() == 1) {
            cents = "0" + cents;
        }

        return "%s,%s".formatted(euros, cents);
    }

    private String dateTimeToHumanReadableString(ZonedDateTime dateTime, Locale locale) {
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd LLLL yyyy, kk:mm:ss").withLocale(locale);
        return dateTime.format(formatter);
    }
}
