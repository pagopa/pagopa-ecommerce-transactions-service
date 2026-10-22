package it.pagopa.transactions.services.v2;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.BaseTransactionView;
import it.pagopa.ecommerce.commons.documents.v2.ClosureErrorData;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptData;
import it.pagopa.ecommerce.commons.domain.v2.PaymentNotice;
import it.pagopa.ecommerce.commons.domain.v2.RptId;
import it.pagopa.ecommerce.commons.domain.v2.TransactionAmount;
import it.pagopa.ecommerce.commons.domain.v2.TransactionId;
import it.pagopa.ecommerce.commons.mdcutilities.LogTracingUtils;
import it.pagopa.generated.transactions.v2.server.model.*;
import it.pagopa.transactions.commands.TransactionActivateCommand;
import it.pagopa.transactions.commands.data.NewTransactionRequestData;
import it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler;
import it.pagopa.transactions.exceptions.InvalidRequestException;
import it.pagopa.transactions.exceptions.NotImplementedException;
import it.pagopa.transactions.exceptions.TransactionNotFoundException;
import it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.ConfidentialMailUtils;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.WispDeprecation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service(TransactionsService.QUALIFIER_NAME)
@Slf4j
public class TransactionsService {

    public static final String QUALIFIER_NAME = "TransactionsServiceV2";
    private final TransactionsUtils transactionsUtils;
    private final TransactionsViewRepository transactionsViewRepository;

    @Autowired
    public TransactionsService(
            TransactionsUtils transactionsUtils,
            TransactionsViewRepository transactionsViewRepository
    ) {
        this.transactionsUtils = transactionsUtils;
        this.transactionsViewRepository = transactionsViewRepository;
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
                                throw new InvalidRequestException("Unknown input origin", e);
                            }
                        }
                ).orElseThrow(() -> new InvalidRequestException("Null value as input origin"));
    }

    @CircuitBreaker(name = "ecommerce-db")
    @Retry(name = "getTransactionInfo")
    public Mono<it.pagopa.generated.transactions.v2.server.model.TransactionInfoDto> getTransactionInfo(
                                                                                                        String transactionId,
                                                                                                        UUID xUserId
    ) {
        return getBaseTransactionView(transactionId, xUserId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId, xUserId)))
                .map(this::buildTransactionInfoDtoFromView);
    }

    private Mono<BaseTransactionView> getBaseTransactionView(String transactionId, UUID xUserId) {
        return transactionsViewRepository.findById(transactionId)
                .doOnNext(transaction ->
                    LogTracingUtils.loggerTracingUtils()
                            .success()
                            .dependency(LogTracingUtils.MONGO_DEPENDENCY)
                            .logInfo(log, "Retrieved transaction document")
                )
                .filter(transactionDocument -> switch (transactionDocument) {
                    case it.pagopa.ecommerce.commons.documents.v1.Transaction ignored -> xUserId == null;
                    case it.pagopa.ecommerce.commons.documents.v2.Transaction t ->
                            Optional.ofNullable(xUserId).map(UUID::toString).equals(Optional.ofNullable(t.getUserId()));
                    default ->
                            throw new NotImplementedException("Handling for transaction document version: [%s] not implemented yet".formatted(transactionDocument.getClass()));
                });
    }

    private it.pagopa.generated.transactions.v2.server.model.TransactionInfoDto buildTransactionInfoDtoFromView(
                                                                                                                BaseTransactionView baseTransactionView
    ) {
        if (baseTransactionView instanceof it.pagopa.ecommerce.commons.documents.v2.Transaction transaction) {
            List<PaymentInfoDto> payments = transaction.getPaymentNotices().stream().map(
                    paymentNotice -> new it.pagopa.generated.transactions.v2.server.model.PaymentInfoDto()
                            .amount(paymentNotice.getAmount())
                            .reason(paymentNotice.getDescription())
                            .paymentToken(paymentNotice.getPaymentToken())
                            .rptId(paymentNotice.getRptId())
                            .isAllCCP(paymentNotice.isAllCCP())
                            .transferList(
                                    paymentNotice.getTransferList().stream().map(
                                            notice -> new it.pagopa.generated.transactions.v2.server.model.TransferDto()
                                                    .transferCategory(
                                                            notice.getTransferCategory()
                                                    )
                                                    .transferAmount(
                                                            notice.getTransferAmount()
                                                    ).digitalStamp(notice.getDigitalStamp())
                                                    .paFiscalCode(notice.getPaFiscalCode())
                                    ).toList()
                            )
            ).toList();
            TransactionInfoGatewayInfoDto gatewayInfoDto = new TransactionInfoGatewayInfoDto()
                    .gateway(transaction.getPaymentGateway())
                    .authorizationCode(transaction.getAuthorizationCode())
                    .authorizationStatus(transaction.getGatewayAuthorizationStatus())
                    .errorCode(transaction.getAuthorizationErrorCode());
            TransactionInfoNodeInfoClosePaymentResultErrorDto closePaymentResultErrorDto = null;
            ClosureErrorData closureErrorData = transaction.getClosureErrorData();
            if (closureErrorData != null) {
                closePaymentResultErrorDto = new TransactionInfoNodeInfoClosePaymentResultErrorDto()
                        .description(closureErrorData.getErrorDescription())
                        .statusCode(
                                Optional.ofNullable(closureErrorData.getHttpErrorCode())
                                        .map(httpCode -> BigDecimal.valueOf(httpCode.value())).orElse(null)
                        );
            }
            TransactionInfoNodeInfoDto nodeInfoDto = new TransactionInfoNodeInfoDto()
                    .closePaymentResultError(closePaymentResultErrorDto)
                    .sendPaymentResultOutcome(
                            Optional
                                    .ofNullable(transaction.getSendPaymentResultOutcome())
                                    .map(TransactionUserReceiptData.Outcome::toString)
                                    .map(TransactionInfoNodeInfoDto.SendPaymentResultOutcomeEnum::fromValue)
                                    .orElse(null)
                    );
            return new it.pagopa.generated.transactions.v2.server.model.TransactionInfoDto()
                    .transactionId(transaction.getTransactionId())
                    .payments(payments)
                    .feeTotal(transaction.getFeeTotal())
                    .clientId(
                            it.pagopa.generated.transactions.v2.server.model.TransactionInfoDto.ClientIdEnum.valueOf(
                                    transaction.getClientId().getEffectiveClient().toString()
                            )
                    )
                    .status(transactionsUtils.convertEnumerationV2(transaction.getStatus()))
                    .idCart(transaction.getIdCart())
                    .gatewayInfo(gatewayInfoDto)
                    .nodeInfo(nodeInfoDto);
        }

        throw new NotImplementedException(
                "Handling for transaction document version: [%s] not implemented yet"
                        .formatted(baseTransactionView.getClass())
        );
    }

}
