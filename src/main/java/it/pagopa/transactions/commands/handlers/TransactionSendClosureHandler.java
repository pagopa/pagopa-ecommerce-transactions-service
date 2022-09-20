package it.pagopa.transactions.commands.handlers;

import it.pagopa.generated.ecommerce.nodo.v1.dto.AdditionalPaymentInformationsDto;
import it.pagopa.generated.ecommerce.nodo.v1.dto.ClosePaymentRequestDto;
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
import it.pagopa.transactions.domain.Transaction;
import it.pagopa.transactions.exceptions.AlreadyProcessedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.utils.TransactionEventCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;

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
    NotificationsServiceClient notificationsServiceClient;

    @Override
    public Mono<TransactionClosureSentEvent> handle(TransactionClosureSendCommand command) {
        Transaction transaction = command.getData().transaction();

        if (transaction.getStatus() != TransactionStatusDto.AUTHORIZED) {
            log.error("Error: requesting closure status update for transaction in state {}", transaction.getStatus());
            return Mono.error(new AlreadyProcessedException(transaction.getRptId()));
        } else {
            UpdateAuthorizationRequestDto updateAuthorizationRequest = command.getData().updateAuthorizationRequest();
            return authorizationRequestedEventStoreRepository.findByTransactionIdAndEventCode(
                            transaction.getTransactionId().value().toString(),
                            TransactionEventCode.TRANSACTION_AUTHORIZATION_REQUESTED_EVENT
                    )
                    .switchIfEmpty(Mono.error(new TransactionNotFoundException(transaction.getPaymentToken().value())))
                    .flatMap(authorizationRequestedEvent -> {
                        TransactionAuthorizationRequestData authorizationRequestData = authorizationRequestedEvent.getData();

                        ClosePaymentRequestDto closePaymentRequest = new ClosePaymentRequestDto()
                                .paymentTokens(List.of(transaction.getPaymentToken().value()))
                                .outcome(authorizationResultToOutcome(updateAuthorizationRequest.getAuthorizationResult()))
                                .identificativoPsp(authorizationRequestData.getPspId())
                                .tipoVersamento(ClosePaymentRequestDto.TipoVersamentoEnum.fromValue(authorizationRequestData.getPaymentTypeCode()))
                                .identificativoIntermediario(authorizationRequestData.getBrokerName())
                                .identificativoCanale(authorizationRequestData.getPspChannelCode())
                                .pspTransactionId(transaction.getTransactionId().value().toString())
                                .totalAmount(new BigDecimal(transaction.getAmount().value() + authorizationRequestData.getFee()))
                                .fee(new BigDecimal(authorizationRequestData.getFee()))
                                .timestampOperation(updateAuthorizationRequest.getTimestampOperation())
                                .additionalPaymentInformations(
                                        new AdditionalPaymentInformationsDto()
                                                .outcomePaymentGateway(updateAuthorizationRequest.getAuthorizationResult().toString())
                                                .transactionId(transaction.getTransactionId().value().toString())
                                                .authorizationCode(updateAuthorizationRequest.getAuthorizationCode())
                                );

                        return nodeForPspClient.closePayment(closePaymentRequest);
                    })
                    .flatMap(response -> {
                        TransactionStatusDto newStatus;

                        switch (response.getEsito()) {
                            case OK -> newStatus = TransactionStatusDto.CLOSED;
                            case KO -> newStatus = TransactionStatusDto.CLOSURE_FAILED;
                            default -> {
                                return Mono.error(new RuntimeException("Invalid outcome result enum value"));
                            }
                        }

                        TransactionClosureSendData closureSendData =
                                new TransactionClosureSendData(
                                        response.getEsito(),
                                        newStatus
                                );

                        TransactionClosureSentEvent event = new TransactionClosureSentEvent(
                                transaction.getTransactionId().value().toString(),
                                transaction.getRptId().value(),
                                transaction.getPaymentToken().value(),
                                closureSendData
                        );

                        Mono<NotificationEmailResponseDto> emailResponse = notificationsServiceClient.sendSuccessEmail(
                                new SuccessTemplate(
                                        new TransactionTemplate(
                                                "transactionId",
                                                "timestamp",
                                                "amount",
                                                new PspTemplate(
                                                        "pspName",
                                                        new FeeTemplate("feeAmount")
                                                ),
                                                "RRN",
                                                "authorizationCode",
                                                new PaymentMethodTemplate(
                                                        "paymentMethodName",
                                                        "paymentMethodLogo",
                                                        "paymentMethodAccountHolder",
                                                        false
                                                )
                                        ),
                                        new UserTemplate(
                                                new DataTemplate(
                                                        "firstName",
                                                        "lastName",
                                                        "taxCode"
                                                ),
                                                "email"
                                        ),
                                        new CartTemplate(
                                                List.of(),
                                                "partialAmount"
                                        )
                                )
                        );

                        return emailResponse.flatMap(v -> transactionEventStoreRepository.save(event));
                    });
        }
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
}
