package it.pagopa.transactions.services.v2;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.PaymentNotice;
import it.pagopa.ecommerce.commons.domain.RptId;
import it.pagopa.ecommerce.commons.domain.TransactionAmount;
import it.pagopa.ecommerce.commons.domain.TransactionId;
import it.pagopa.generated.transactions.v2.server.model.*;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.commands.data.NewTransactionRequestData;
import it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service(TransactionsService.QUALIFIER_NAME)
@Slf4j
public class TransactionsService {

    public static final String QUALIFIER_NAME = "TransactionsServiceV2";
    private final it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler transactionActivateHandlerV2;

    private final it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler transactionsActivationProjectionHandlerV2;

    private final TransactionsUtils transactionsUtils;

    private final ConfidentialMailUtils confidentialMailUtils;

    @Autowired
    public TransactionsService(
            @Qualifier(
                TransactionActivateHandler.QUALIFIER_NAME
            ) TransactionActivateHandler transactionActivateHandlerV2,
            @Qualifier(
                TransactionsActivationProjectionHandler.QUALIFIER_NAME
            ) TransactionsActivationProjectionHandler transactionsActivationProjectionHandlerV2,
            TransactionsUtils transactionsUtils,
            ConfidentialMailUtils confidentialMailUtils
    ) {
        this.transactionActivateHandlerV2 = transactionActivateHandlerV2;
        this.transactionsActivationProjectionHandlerV2 = transactionsActivationProjectionHandlerV2;
        this.transactionsUtils = transactionsUtils;
        this.confidentialMailUtils = confidentialMailUtils;
    }

    @CircuitBreaker(name = "node-backend")
    @Retry(name = "newTransaction")
    public Mono<NewTransactionResponseDto> newTransaction(
                                                          NewTransactionRequestDto newTransactionRequestDto,
                                                          ClientIdDto clientIdDto,
                                                          UUID correlationId,
                                                          TransactionId transactionId,
                                                          UUID userId
    ) {
        Transaction.ClientId clientId = Transaction.ClientId.fromString(
                Optional.ofNullable(clientIdDto)
                        .map(ClientIdDto::toString)
                        .orElse(null)
        );

        TransactionActivateCommand transactionActivateCommand = new TransactionActivateCommand(
                newTransactionRequestDto.getPaymentNotices().stream().map(p -> new RptId(p.getRptId())).toList(),
                new NewTransactionRequestData(
                        newTransactionRequestDto.getIdCart(),
                        confidentialMailUtils.toConfidential(newTransactionRequestDto.getEmail()),
                        newTransactionRequestDto.getOrderId(),
                        correlationId,
                        newTransactionRequestDto.getPaymentNotices().stream().map(
                                el -> new PaymentNotice(
                                        null,
                                        new RptId(el.getRptId()),
                                        new TransactionAmount(el.getAmount()),
                                        null,
                                        null,
                                        null,
                                        false,
                                        null
                                )
                        ).toList()
                ),
                clientId.name(),
                transactionId,
                userId
        );
        log.info(
                "Initializing transaction for rptIds: {}. ClientId: {}",
                transactionActivateCommand.getRptIds().stream().map(RptId::value).toList(),
                clientId
        );

        return transactionActivateHandlerV2.handle(transactionActivateCommand)
                .doOnNext(
                        args -> log.info(
                                "Transaction initialized for rptId [{}]",
                                newTransactionRequestDto.getPaymentNotices().get(0).getRptId()
                        )
                )
                .flatMap(
                        es -> {
                            final Mono<BaseTransactionEvent<?>> transactionActivatedEvent = es
                                    .getT1();
                            final String authToken = es.getT2();
                            return transactionActivatedEvent
                                    .flatMap(
                                            t -> projectActivatedEvent(
                                                    (it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent) t,
                                                    authToken
                                            )
                                    );
                        }
                );

    }

    private Mono<NewTransactionResponseDto> projectActivatedEvent(
                                                                  it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent transactionActivatedEvent,
                                                                  String authToken
    ) {
        return transactionsActivationProjectionHandlerV2
                .handle(transactionActivatedEvent)
                .map(
                        transaction -> new NewTransactionResponseDto()
                                .transactionId(transaction.getTransactionId().value())
                                .payments(
                                        transaction.getPaymentNotices().stream().map(
                                                paymentNotice -> new PaymentInfoDto()
                                                        .amount(paymentNotice.transactionAmount().value())
                                                        .reason(paymentNotice.transactionDescription().value())
                                                        .rptId(paymentNotice.rptId().value())
                                                        .paymentToken(paymentNotice.paymentToken().value())
                                                        .isAllCCP(paymentNotice.isAllCCP())
                                                        .transferList(
                                                                paymentNotice.transferList().stream().map(
                                                                        paymentTransferInfo -> new TransferDto()
                                                                                .digitalStamp(
                                                                                        paymentTransferInfo
                                                                                                .digitalStamp()
                                                                                )
                                                                                .paFiscalCode(
                                                                                        paymentTransferInfo
                                                                                                .paFiscalCode()
                                                                                )
                                                                                .transferAmount(
                                                                                        paymentTransferInfo
                                                                                                .transferAmount()
                                                                                )
                                                                                .transferCategory(
                                                                                        paymentTransferInfo
                                                                                                .transferCategory()
                                                                                )
                                                                ).toList()
                                                        )
                                        ).toList()
                                )
                                .authToken(authToken)
                                .status(transactionsUtils.convertEnumerationV2(transaction.getStatus()))
                                // .feeTotal()//TODO da dove prendere le fees?
                                .clientId(convertClientId(transaction.getClientId()))
                                .idCart(transaction.getTransactionActivatedData().getIdCart())
                );
    }

    public NewTransactionResponseDto.ClientIdEnum convertClientId(
                                                                  Transaction.ClientId clientId
    ) {
        return Optional.ofNullable(clientId)
                .map(
                        value -> {
                            try {
                                return NewTransactionResponseDto.ClientIdEnum
                                        .fromValue(value.getEffectiveClient().name());
                            } catch (IllegalArgumentException e) {
                                log.error("Unknown input origin ", e);
                                throw new InvalidRequestException("Unknown input origin", e);
                            }
                        }
                ).orElseThrow(() -> new InvalidRequestException("Null value as input origin"));
    }
}
