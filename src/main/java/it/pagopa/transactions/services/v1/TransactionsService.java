package it.pagopa.transactions.services.v1;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.vavr.Tuple;
import io.vavr.control.Either;
import it.pagopa.ecommerce.commons.documents.BaseTransactionEvent;
import it.pagopa.ecommerce.commons.documents.BaseTransactionView;
import it.pagopa.ecommerce.commons.documents.v1.TransactionUserReceiptRequestedEvent;
import it.pagopa.ecommerce.commons.documents.v2.Transaction;
import it.pagopa.ecommerce.commons.domain.*;
import it.pagopa.ecommerce.commons.domain.v1.TransactionEventCode;
import it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction;
import it.pagopa.ecommerce.commons.redis.templatewrappers.PaymentRequestInfoRedisTemplateWrapper;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.BundleDto;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.CalculateFeeRequestDto;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.CalculateFeeResponseDto;
import it.pagopa.generated.ecommerce.paymentmethods.v1.dto.TransferListItemDto;
import it.pagopa.generated.transactions.server.model.*;
import it.pagopa.generated.wallet.v1.dto.WalletAuthCardDataDto;
import it.pagopa.transactions.client.EcommercePaymentMethodsClient;
import it.pagopa.transactions.client.WalletClient;
import it.pagopa.transactions.commands.*;
import it.pagopa.transactions.commands.data.*;
import it.pagopa.transactions.exceptions.*;
import it.pagopa.transactions.repositories.TransactionsEventStoreRepository;
import it.pagopa.transactions.repositories.TransactionsViewRepository;
import it.pagopa.transactions.utils.EventVersion;
import it.pagopa.transactions.utils.PaymentSessionData;
import it.pagopa.transactions.utils.TransactionsUtils;
import it.pagopa.transactions.utils.UUIDUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service(TransactionsService.QUALIFIER_NAME)
@Slf4j
public class TransactionsService {

    public static final String QUALIFIER_NAME = "TransactionsServiceV1";
    private final it.pagopa.transactions.commands.handlers.v1.TransactionActivateHandler transactionActivateHandlerV1;

    private final it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler transactionActivateHandlerV2;

    private final it.pagopa.transactions.commands.handlers.v1.TransactionRequestAuthorizationHandler requestAuthHandlerV1;
    private final it.pagopa.transactions.commands.handlers.v2.TransactionRequestAuthorizationHandler requestAuthHandlerV2;

    private final it.pagopa.transactions.commands.handlers.v1.TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandlerV1;

    private final it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandlerV2;

    private final it.pagopa.transactions.commands.handlers.v1.TransactionSendClosureHandler transactionSendClosureHandlerV1;

    private final it.pagopa.transactions.commands.handlers.v2.TransactionSendClosureRequestHandler transactionSendClosureRequestHandler;

    private final it.pagopa.transactions.commands.handlers.v1.TransactionRequestUserReceiptHandler transactionRequestUserReceiptHandlerV1;

    private final it.pagopa.transactions.commands.handlers.v2.TransactionRequestUserReceiptHandler transactionRequestUserReceiptHandlerV2;

    private final it.pagopa.transactions.commands.handlers.v1.TransactionUserCancelHandler transactionCancelHandlerV1;

    private final it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler transactionCancelHandlerV2;

    private final it.pagopa.transactions.projections.handlers.v1.AuthorizationRequestProjectionHandler authorizationProjectionHandlerV1;

    private final it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler authorizationProjectionHandlerV2;

    private final it.pagopa.transactions.projections.handlers.v1.AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandlerV1;

    private final it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandlerV2;

    private final it.pagopa.transactions.projections.handlers.v1.RefundRequestProjectionHandler refundRequestProjectionHandlerV1;

    private final it.pagopa.transactions.projections.handlers.v1.ClosureSendProjectionHandler closureSendProjectionHandlerV1;

    private final it.pagopa.transactions.projections.handlers.v2.ClosureRequestedProjectionHandler closureRequestedProjectionHandler;

    private final it.pagopa.transactions.projections.handlers.v1.ClosureErrorProjectionHandler closureErrorProjectionHandlerV1;

    private final it.pagopa.transactions.projections.handlers.v1.CancellationRequestProjectionHandler cancellationRequestProjectionHandlerV1;

    private final it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler cancellationRequestProjectionHandlerV2;

    private final it.pagopa.transactions.projections.handlers.v1.TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandlerV1;

    private final it.pagopa.transactions.projections.handlers.v2.TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandlerV2;

    private final it.pagopa.transactions.projections.handlers.v1.TransactionsActivationProjectionHandler transactionsActivationProjectionHandlerV1;

    private final it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler transactionsActivationProjectionHandlerV2;

    private final TransactionsViewRepository transactionsViewRepository;

    private final EcommercePaymentMethodsClient ecommercePaymentMethodsClient;

    private final WalletClient walletClient;

    private final UUIDUtils uuidUtils;

    private final TransactionsUtils transactionsUtils;

    private final TransactionsEventStoreRepository<Object> eventsRepository;

    private final Integer paymentTokenValidity;
    private final EventVersion eventVersion;

    private final PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper;

    @Autowired
    public TransactionsService(
            @Qualifier(
                it.pagopa.transactions.commands.handlers.v1.TransactionActivateHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.commands.handlers.v1.TransactionActivateHandler transactionActivateHandlerV1,
            @Qualifier(
                it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.commands.handlers.v2.TransactionActivateHandler transactionActivateHandlerV2,
            it.pagopa.transactions.commands.handlers.v1.TransactionRequestAuthorizationHandler requestAuthHandlerV1,
            it.pagopa.transactions.commands.handlers.v2.TransactionRequestAuthorizationHandler requestAuthHandlerV2,
            @Qualifier(
                it.pagopa.transactions.commands.handlers.v1.TransactionUpdateAuthorizationHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.commands.handlers.v1.TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandlerV1,
            @Qualifier(
                it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.commands.handlers.v2.TransactionUpdateAuthorizationHandler transactionUpdateAuthorizationHandlerV2,
            @Qualifier(
                it.pagopa.transactions.commands.handlers.v1.TransactionSendClosureHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.commands.handlers.v1.TransactionSendClosureHandler transactionSendClosureHandlerV1,
            it.pagopa.transactions.commands.handlers.v2.TransactionSendClosureRequestHandler transactionSendClosureRequestHandler,
            @Qualifier(
                it.pagopa.transactions.commands.handlers.v1.TransactionRequestUserReceiptHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.commands.handlers.v1.TransactionRequestUserReceiptHandler transactionRequestUserReceiptHandlerV1,
            @Qualifier(
                it.pagopa.transactions.commands.handlers.v2.TransactionRequestUserReceiptHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.commands.handlers.v2.TransactionRequestUserReceiptHandler transactionRequestUserReceiptHandlerV2,
            @Qualifier(
                it.pagopa.transactions.commands.handlers.v1.TransactionUserCancelHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.commands.handlers.v1.TransactionUserCancelHandler transactionCancelHandlerV1,
            @Qualifier(
                it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.commands.handlers.v2.TransactionUserCancelHandler transactionCancelHandlerV2,

            @Qualifier(
                it.pagopa.transactions.projections.handlers.v1.AuthorizationRequestProjectionHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.projections.handlers.v1.AuthorizationRequestProjectionHandler authorizationProjectionHandlerV1,
            @Qualifier(
                it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.projections.handlers.v2.AuthorizationRequestProjectionHandler authorizationProjectionHandlerV2,
            @Qualifier(
                it.pagopa.transactions.projections.handlers.v1.AuthorizationUpdateProjectionHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.projections.handlers.v1.AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandlerV1,
            @Qualifier(
                it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.projections.handlers.v2.AuthorizationUpdateProjectionHandler authorizationUpdateProjectionHandlerV2,
            @Qualifier(
                it.pagopa.transactions.projections.handlers.v1.RefundRequestProjectionHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.projections.handlers.v1.RefundRequestProjectionHandler refundRequestProjectionHandlerV1,
            @Qualifier(
                it.pagopa.transactions.projections.handlers.v1.ClosureSendProjectionHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.projections.handlers.v1.ClosureSendProjectionHandler closureSendProjectionHandlerV1,
            it.pagopa.transactions.projections.handlers.v2.ClosureRequestedProjectionHandler closureRequestedProjectionHandler,
            @Qualifier(
                it.pagopa.transactions.projections.handlers.v1.ClosureErrorProjectionHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.projections.handlers.v1.ClosureErrorProjectionHandler closureErrorProjectionHandlerV1,
            @Qualifier(
                it.pagopa.transactions.projections.handlers.v1.CancellationRequestProjectionHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.projections.handlers.v1.CancellationRequestProjectionHandler cancellationRequestProjectionHandlerV1,
            @Qualifier(
                it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.projections.handlers.v2.CancellationRequestProjectionHandler cancellationRequestProjectionHandlerV2,
            @Qualifier(
                it.pagopa.transactions.projections.handlers.v1.TransactionUserReceiptProjectionHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.projections.handlers.v1.TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandlerV1,
            @Qualifier(
                it.pagopa.transactions.projections.handlers.v2.TransactionUserReceiptProjectionHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.projections.handlers.v2.TransactionUserReceiptProjectionHandler transactionUserReceiptProjectionHandlerV2,
            @Qualifier(
                it.pagopa.transactions.projections.handlers.v1.TransactionsActivationProjectionHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.projections.handlers.v1.TransactionsActivationProjectionHandler transactionsActivationProjectionHandlerV1,
            @Qualifier(
                it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler.QUALIFIER_NAME
            ) it.pagopa.transactions.projections.handlers.v2.TransactionsActivationProjectionHandler transactionsActivationProjectionHandlerV2,
            TransactionsViewRepository transactionsViewRepository,
            EcommercePaymentMethodsClient ecommercePaymentMethodsClient,
            WalletClient walletClient,
            UUIDUtils uuidUtils,
            TransactionsUtils transactionsUtils,
            TransactionsEventStoreRepository<Object> eventsRepository,
            @Value("${payment.token.validity}") Integer paymentTokenValidity,
            @Value("${ecommerce.event.version}") EventVersion eventVersion,
            PaymentRequestInfoRedisTemplateWrapper paymentRequestInfoRedisTemplateWrapper
    ) {
        this.transactionActivateHandlerV1 = transactionActivateHandlerV1;
        this.transactionActivateHandlerV2 = transactionActivateHandlerV2;
        this.requestAuthHandlerV1 = requestAuthHandlerV1;
        this.requestAuthHandlerV2 = requestAuthHandlerV2;
        this.transactionUpdateAuthorizationHandlerV1 = transactionUpdateAuthorizationHandlerV1;
        this.transactionUpdateAuthorizationHandlerV2 = transactionUpdateAuthorizationHandlerV2;
        this.transactionSendClosureHandlerV1 = transactionSendClosureHandlerV1;
        this.transactionSendClosureRequestHandler = transactionSendClosureRequestHandler;
        this.transactionRequestUserReceiptHandlerV1 = transactionRequestUserReceiptHandlerV1;
        this.transactionRequestUserReceiptHandlerV2 = transactionRequestUserReceiptHandlerV2;
        this.transactionCancelHandlerV1 = transactionCancelHandlerV1;
        this.transactionCancelHandlerV2 = transactionCancelHandlerV2;
        this.authorizationProjectionHandlerV1 = authorizationProjectionHandlerV1;
        this.authorizationProjectionHandlerV2 = authorizationProjectionHandlerV2;
        this.authorizationUpdateProjectionHandlerV1 = authorizationUpdateProjectionHandlerV1;
        this.authorizationUpdateProjectionHandlerV2 = authorizationUpdateProjectionHandlerV2;
        this.refundRequestProjectionHandlerV1 = refundRequestProjectionHandlerV1;
        this.closureSendProjectionHandlerV1 = closureSendProjectionHandlerV1;
        this.closureRequestedProjectionHandler = closureRequestedProjectionHandler;
        this.closureErrorProjectionHandlerV1 = closureErrorProjectionHandlerV1;
        this.cancellationRequestProjectionHandlerV1 = cancellationRequestProjectionHandlerV1;
        this.cancellationRequestProjectionHandlerV2 = cancellationRequestProjectionHandlerV2;
        this.transactionUserReceiptProjectionHandlerV1 = transactionUserReceiptProjectionHandlerV1;
        this.transactionUserReceiptProjectionHandlerV2 = transactionUserReceiptProjectionHandlerV2;
        this.transactionsActivationProjectionHandlerV1 = transactionsActivationProjectionHandlerV1;
        this.transactionsActivationProjectionHandlerV2 = transactionsActivationProjectionHandlerV2;
        this.transactionsViewRepository = transactionsViewRepository;
        this.ecommercePaymentMethodsClient = ecommercePaymentMethodsClient;
        this.walletClient = walletClient;
        this.uuidUtils = uuidUtils;
        this.transactionsUtils = transactionsUtils;
        this.eventsRepository = eventsRepository;
        this.paymentTokenValidity = paymentTokenValidity;
        this.eventVersion = eventVersion;
        this.paymentRequestInfoRedisTemplateWrapper = paymentRequestInfoRedisTemplateWrapper;
    }

    @CircuitBreaker(name = "node-backend")
    @Retry(name = "newTransaction")
    public Mono<NewTransactionResponseDto> newTransaction(
                                                          NewTransactionRequestDto newTransactionRequestDto,
                                                          ClientIdDto clientIdDto,
                                                          TransactionId transactionId
    ) {
        Transaction.ClientId clientId = Transaction.ClientId.fromString(
                Optional.ofNullable(clientIdDto)
                        .map(ClientIdDto::toString)
                        .orElse(null)
        );
        log.debug(
                "Initializing transaction for rptId: {}. ClientId: {}",
                newTransactionRequestDto.getPaymentNotices().get(0).getRptId(),
                clientId
        );
        TransactionActivateCommand transactionActivateCommand = new TransactionActivateCommand(
                new RptId(newTransactionRequestDto.getPaymentNotices().get(0).getRptId()),
                new NewTransactionRequestData(
                        newTransactionRequestDto.getIdCart(),
                        newTransactionRequestDto.getEmail(),
                        null,
                        null,
                        newTransactionRequestDto.getPaymentNotices().stream().map(
                                el -> new PaymentNotice(
                                        null,
                                        new RptId(el.getRptId()),
                                        new TransactionAmount(el.getAmount()),
                                        null,
                                        null,
                                        null,
                                        false
                                )
                        ).toList()
                ),
                clientId.name(),
                transactionId
        );

        return switch (eventVersion) {
            case V1 -> transactionActivateHandlerV1.handle(transactionActivateCommand)
                    .doOnNext(
                            args -> log.info(
                                    "Transaction initialized for rptId: [{}]",
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
                                                t -> projectActivatedEventV1(
                                                        (it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent) t,
                                                        authToken
                                                )
                                        );
                            }
                    );

            case V2 -> transactionActivateHandlerV2.handle(transactionActivateCommand)
                    .doOnNext(
                            args -> log.info(
                                    "Transaction initialized for rptId: [{}]",
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
                                                t -> projectActivatedEventV2(
                                                        (it.pagopa.ecommerce.commons.documents.v2.TransactionActivatedEvent) t,
                                                        authToken
                                                )
                                        );
                            }
                    );

        };
    }

    @CircuitBreaker(name = "ecommerce-db")
    @Retry(name = "getTransactionInfo")
    public Mono<TransactionInfoDto> getTransactionInfo(String transactionId) {
        log.debug("Get Transaction Invoked with id {} ", transactionId);
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .map(this::buildTransactionInfoDtoFromView);
    }

    private TransactionInfoDto buildTransactionInfoDtoFromView(BaseTransactionView baseTransactionView) {
        return switch (baseTransactionView) {
            case it.pagopa.ecommerce.commons.documents.v1.Transaction transaction -> new TransactionInfoDto()
                    .transactionId(transaction.getTransactionId())
                    .payments(
                            transaction.getPaymentNotices().stream().map(
                                    paymentNotice -> new PaymentInfoDto()
                                            .amount(paymentNotice.getAmount())
                                            .reason(paymentNotice.getDescription())
                                            .paymentToken(paymentNotice.getPaymentToken())
                                            .rptId(paymentNotice.getRptId())
                                            .isAllCCP(paymentNotice.isAllCCP())
                                            .transferList(
                                                    paymentNotice.getTransferList().stream().map(
                                                            notice -> new TransferDto()
                                                                    .transferCategory(
                                                                            notice.getTransferCategory()
                                                                    )
                                                                    .transferAmount(
                                                                            notice.getTransferAmount()
                                                                    ).digitalStamp(notice.getDigitalStamp())
                                                                    .paFiscalCode(notice.getPaFiscalCode())
                                                    ).toList()
                                            )
                            ).toList()
                    )
                    .feeTotal(transaction.getFeeTotal())
                    .clientId(
                            TransactionInfoDto.ClientIdEnum.valueOf(
                                    transaction.getClientId().toString()
                            )
                    )
                    .status(transactionsUtils.convertEnumerationV1(transaction.getStatus()))
                    .idCart(transaction.getIdCart())
                    .gateway(transaction.getPaymentGateway())
                    .sendPaymentResultOutcome(
                            transaction.getSendPaymentResultOutcome() == null ? null
                                    : TransactionInfoDto.SendPaymentResultOutcomeEnum
                                    .valueOf(transaction.getSendPaymentResultOutcome().name())
                    )
                    .authorizationCode(transaction.getAuthorizationCode())
                    .errorCode(transaction.getAuthorizationErrorCode());
            case it.pagopa.ecommerce.commons.documents.v2.Transaction transaction -> new TransactionInfoDto()
                    .transactionId(transaction.getTransactionId())
                    .payments(
                            transaction.getPaymentNotices().stream().map(
                                    paymentNotice -> new PaymentInfoDto()
                                            .amount(paymentNotice.getAmount())
                                            .reason(paymentNotice.getDescription())
                                            .paymentToken(paymentNotice.getPaymentToken())
                                            .rptId(paymentNotice.getRptId())
                                            .isAllCCP(paymentNotice.isAllCCP())
                                            .transferList(
                                                    paymentNotice.getTransferList().stream().map(
                                                            notice -> new TransferDto()
                                                                    .transferCategory(
                                                                            notice.getTransferCategory()
                                                                    )
                                                                    .transferAmount(
                                                                            notice.getTransferAmount()
                                                                    ).digitalStamp(notice.getDigitalStamp())
                                                                    .paFiscalCode(notice.getPaFiscalCode())
                                                    ).toList()
                                            )
                            ).toList()
                    )
                    .feeTotal(transaction.getFeeTotal())
                    .clientId(
                            TransactionInfoDto.ClientIdEnum.valueOf(
                                    transaction.getClientId().toString()
                            )
                    )
                    .status(transactionsUtils.convertEnumerationV1(transaction.getStatus()))
                    .idCart(transaction.getIdCart())
                    .gateway(transaction.getPaymentGateway())
                    .sendPaymentResultOutcome(
                            transaction.getSendPaymentResultOutcome() == null ? null
                                    : TransactionInfoDto.SendPaymentResultOutcomeEnum
                                    .valueOf(transaction.getSendPaymentResultOutcome().name())
                    )
                    .authorizationCode(transaction.getAuthorizationCode())
                    .errorCode(transaction.getAuthorizationErrorCode())
                    .gatewayAuthorizationStatus(transaction.getGatewayAuthorizationStatus());
            default -> throw new IllegalStateException("Unexpected value: " + baseTransactionView);
        };
    }

    @Retry(name = "cancelTransaction")
    public Mono<Void> cancelTransaction(String transactionId) {
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .flatMap(
                        transactionDocument -> {
                            TransactionUserCancelCommand transactionCancelCommand = new TransactionUserCancelCommand(
                                    null,
                                    new TransactionId(transactionId)
                            );

                            return switch (transactionDocument) {
                                case it.pagopa.ecommerce.commons.documents.v1.Transaction t ->
                                        transactionCancelHandlerV1
                                                .handle(transactionCancelCommand).flatMap(event -> cancellationRequestProjectionHandlerV1
                                                        .handle((it.pagopa.ecommerce.commons.documents.v1.TransactionUserCanceledEvent) event));

                                case it.pagopa.ecommerce.commons.documents.v2.Transaction t ->
                                        transactionCancelHandlerV2
                                                .handle(transactionCancelCommand).flatMap(event -> cancellationRequestProjectionHandlerV2
                                                        .handle((it.pagopa.ecommerce.commons.documents.v2.TransactionUserCanceledEvent) event));
                                default ->
                                        Mono.error(new BadGatewayException("Error while processing request unexpected transaction version type", HttpStatus.BAD_GATEWAY));
                            };
                        }
                )
                .then();

    }

    @CircuitBreaker(name = "transactions-backend")
    @Retry(name = "requestTransactionAuthorization")
    public Mono<RequestAuthorizationResponseDto> requestTransactionAuthorization(
            String transactionId,
            String paymentGatewayId,
            RequestAuthorizationRequestDto requestAuthorizationRequestDto
    ) {
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .flatMap(
                        transaction -> {
                            Integer amountTotal = transactionsUtils.getTransactionTotalAmount(transaction);

                            Boolean isAllCCP = transactionsUtils.isAllCcp(transaction, 0);
                            log.debug(
                                    "Authorization request amount validation for transactionId: {}",
                                    transactionId
                            );
                            boolean amountMismatch = !amountTotal.equals(requestAuthorizationRequestDto.getAmount());
                            boolean allCCPMismatch = !isAllCCP.equals(requestAuthorizationRequestDto.getIsAllCCP());
                            return amountMismatch || allCCPMismatch
                                    ? (amountMismatch ? Mono.error(
                                    new TransactionAmountMismatchException(
                                            requestAuthorizationRequestDto.getAmount(),
                                            amountTotal
                                    )
                            )
                                    : Mono.error(
                                    new PaymentNoticeAllCCPMismatchException(
                                            transactionsUtils.getRptId(transaction, 0),
                                            requestAuthorizationRequestDto.getIsAllCCP(),
                                            isAllCCP
                                    )
                            ))
                                    : Mono.just(transaction);
                        }
                )
                .flatMap(
                        transaction -> {
                            log.info(
                                    "Authorization psp validation for transactionId: {}",
                                    transactionId
                            );
                            Integer amountTotal = transactionsUtils.getTransactionTotalAmount(transaction);
                            String clientId = transactionsUtils.getClientId(transaction);
                            return retrieveInformationFromAuthorizationRequest(requestAuthorizationRequestDto, clientId)
                                    .flatMap(
                                            paymentSessionData -> ecommercePaymentMethodsClient
                                                    .calculateFee(
                                                            requestAuthorizationRequestDto.getPaymentInstrumentId(),
                                                            transactionId,
                                                            new CalculateFeeRequestDto()
                                                                    .touchpoint(
                                                                            clientId
                                                                    )
                                                                    .bin(
                                                                            paymentSessionData.cardBin()
                                                                    )
                                                                    .idPspList(
                                                                            List.of(
                                                                                    requestAuthorizationRequestDto
                                                                                            .getPspId()
                                                                            )
                                                                    )
                                                                    .paymentAmount(amountTotal.longValue())
                                                                    .primaryCreditorInstitution(
                                                                            transactionsUtils.getRptId(transaction, 0)
                                                                                    .substring(0, 11)
                                                                    )
                                                                    .transferList(
                                                                            transactionsUtils
                                                                                    .getPaymentNotices(transaction)
                                                                                    .get(0)
                                                                                    .getTransferList()
                                                                                    .stream()
                                                                                    .map(
                                                                                            t -> new TransferListItemDto()
                                                                                                    .creditorInstitution(
                                                                                                            t.getPaFiscalCode()
                                                                                                    )
                                                                                                    .digitalStamp(
                                                                                                            t.getDigitalStamp()
                                                                                                    )
                                                                                                    .transferCategory(
                                                                                                            t.getTransferCategory()
                                                                                                    )
                                                                                    ).toList()
                                                                    )
                                                                    .isAllCCP(
                                                                            transactionsUtils.isAllCcp(transaction, 0)
                                                                    ),
                                                            Integer.MAX_VALUE
                                                    )
                                                    .map(
                                                            calculateFeeResponseDto -> Tuples.of(
                                                                    calculateFeeResponseDto,
                                                                    paymentSessionData
                                                            )
                                                    )
                                    )
                                    .map(
                                            data -> {
                                                CalculateFeeResponseDto calculateFeeResponse = data.getT1();
                                                PaymentSessionData paymentSessionData = data.getT2();
                                                return new AuthorizationRequestSessionData(
                                                        calculateFeeResponse.getPaymentMethodName(),
                                                        calculateFeeResponse.getPaymentMethodDescription(),
                                                        calculateFeeResponse.getBundles().stream()
                                                                .filter(
                                                                        psp -> requestAuthorizationRequestDto
                                                                                .getPspId()
                                                                                .equals(
                                                                                        psp.getIdPsp()
                                                                                )
                                                                                && Long.valueOf(
                                                                                        requestAuthorizationRequestDto
                                                                                                .getFee()
                                                                                )
                                                                                .equals(
                                                                                        psp.getTaxPayerFee()
                                                                                )
                                                                ).findFirst(),
                                                        paymentSessionData.brand(),
                                                        Optional.ofNullable(paymentSessionData.sessionId()),
                                                        Optional.ofNullable(paymentSessionData.contractId())
                                                );
                                            }
                                    )
                                    .filter(authSessionData -> authSessionData.bundle().isPresent())
                                    .switchIfEmpty(
                                            Mono.error(
                                                    new UnsatisfiablePspRequestException(
                                                            new PaymentToken(transactionId),
                                                            requestAuthorizationRequestDto.getLanguage(),
                                                            requestAuthorizationRequestDto.getFee()
                                                    )
                                            )
                                    )
                                    .map(
                                            authSessionData -> Tuples.of(
                                                    transaction,
                                                    authSessionData
                                            )

                                    );
                        }
                )
                .flatMap(
                        args -> {
                            it.pagopa.ecommerce.commons.documents.BaseTransactionView transactionDocument = args
                                    .getT1();
                            AuthorizationRequestSessionData authorizationRequestSessionData = args.getT2();
                            String paymentMethodName = authorizationRequestSessionData.paymentMethodName();
                            String paymentMethodDescription = authorizationRequestSessionData.paymentMethodDescription();
                            BundleDto bundle = authorizationRequestSessionData.bundle().orElseThrow();
                            Optional<String> sessionId = authorizationRequestSessionData.npgSessionId();
                            String brand = authorizationRequestSessionData.brand();
                            Optional<String> contractId = authorizationRequestSessionData.npgContractId();
                            log.info(
                                    "Requesting authorization for transactionId: {}",
                                    transactionDocument.getTransactionId()
                            );

                            AuthorizationRequestData authorizationData = new AuthorizationRequestData(
                                    new TransactionId(
                                            transactionDocument.getTransactionId()
                                    ),
                                    transactionsUtils.getPaymentNotices(transactionDocument).stream().map(
                                            paymentNotice -> new PaymentNotice(
                                                    new PaymentToken(paymentNotice.getPaymentToken()),
                                                    new RptId(paymentNotice.getRptId()),
                                                    new TransactionAmount(paymentNotice.getAmount()),
                                                    new TransactionDescription(paymentNotice.getDescription()),
                                                    new PaymentContextCode(
                                                            paymentNotice.getPaymentContextCode()
                                                    ),
                                                    paymentNotice.getTransferList().stream()
                                                            .map(
                                                                    transfer -> new PaymentTransferInfo(
                                                                            transfer.getPaFiscalCode(),
                                                                            transfer.getDigitalStamp(),
                                                                            transfer.getTransferAmount(),
                                                                            transfer.getTransferCategory()
                                                                    )
                                                            ).toList(),
                                                    paymentNotice.isAllCCP()
                                            )
                                    ).toList(),
                                    transactionsUtils.getEmail(transactionDocument),
                                    requestAuthorizationRequestDto.getFee(),
                                    requestAuthorizationRequestDto.getPaymentInstrumentId(),
                                    requestAuthorizationRequestDto.getPspId(),
                                    bundle.getPaymentMethod(),
                                    bundle.getIdBrokerPsp(),
                                    bundle.getIdChannel(),
                                    paymentMethodName,
                                    paymentMethodDescription,
                                    bundle.getBundleName(),
                                    bundle.getOnUs(),
                                    paymentGatewayId,
                                    sessionId,
                                    contractId,
                                    brand,
                                    requestAuthorizationRequestDto.getDetails()
                            );

                            // FIXME Handle multiple rtpId
                            TransactionRequestAuthorizationCommand transactionRequestAuthorizationCommand = new TransactionRequestAuthorizationCommand(
                                    new RptId(transactionsUtils.getRptId(transactionDocument, 0)),
                                    authorizationData
                            );
                            Mono<RequestAuthorizationResponseDto> authPipeline = switch (transactionDocument) {
                                case it.pagopa.ecommerce.commons.documents.v1.Transaction ignored ->
                                        requestAuthHandlerV1
                                                .handle(transactionRequestAuthorizationCommand)
                                                .doOnNext(
                                                        res -> log.info(
                                                                "Requested authorization for transaction: {}",
                                                                transactionDocument.getTransactionId()
                                                        )
                                                )
                                                .flatMap(
                                                        res -> authorizationProjectionHandlerV1
                                                                .handle(authorizationData)
                                                                .thenReturn(res)
                                                );
                                case Transaction ignored -> requestAuthHandlerV2
                                        .handle(transactionRequestAuthorizationCommand).doOnNext(
                                                res -> log.info(
                                                        "Requested authorization for transaction: {}",
                                                        transactionDocument.getTransactionId()
                                                )
                                        )
                                        .flatMap(
                                                res -> authorizationProjectionHandlerV2
                                                        .handle(authorizationData)
                                                        .thenReturn(res)
                                        );
                                default ->
                                        throw new NotImplementedException("Handling for transaction document: [%s] not implemented yet".formatted(transactionDocument.getClass()));
                            };
                            return authPipeline.doOnSuccess(response -> transactionsUtils.getPaymentNotices(transactionDocument).forEach(paymentNotice -> {
                                log.info("Invalidate cache for RptId : {}", paymentNotice.getRptId());
                                paymentRequestInfoRedisTemplateWrapper.deleteById(paymentNotice.getRptId());
                            }));
                        }
                );
    }

    @Retry(name = "updateTransactionAuthorization")
    public Mono<TransactionInfoDto> updateTransactionAuthorization(
                                                                   UUID decodedTransactionId,
                                                                   UpdateAuthorizationRequestDto updateAuthorizationRequestDto
    ) {

        TransactionId transactionId = new TransactionId(decodedTransactionId);
        log.debug("UpdateTransactionAuthorization decoded transaction id: {}", transactionId.value());

        Flux<BaseTransactionEvent<Object>> events = eventsRepository
                .findByTransactionIdOrderByCreationDateAsc(transactionId.value())
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId.value())));

        Mono<ZonedDateTime> authorizationRequestedCreationDate = events
                .filter(
                        event -> event.getEventCode()
                                .equals(TransactionEventCode.TRANSACTION_AUTHORIZATION_REQUESTED_EVENT.toString())
                )
                .next()
                .map(authRequestedEvent -> ZonedDateTime.parse(authRequestedEvent.getCreationDate()))
                .switchIfEmpty(Mono.error(new AlreadyProcessedException(transactionId)));

        Mono<Tuple2<it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction, ZonedDateTime>> transactionV1 = transactionsUtils
                .reduceEvents(
                        events,
                        new it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction(),
                        it.pagopa.ecommerce.commons.domain.v1.Transaction::applyEvent,
                        it.pagopa.ecommerce.commons.domain.v1.Transaction.class
                )
                .filter(t -> !(t instanceof it.pagopa.ecommerce.commons.domain.v1.EmptyTransaction))
                .cast(it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction.class)
                .zipWith(authorizationRequestedCreationDate);

        Mono<Tuple2<it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction, ZonedDateTime>> transactionV2 = transactionsUtils
                .reduceEvents(
                        events,
                        new it.pagopa.ecommerce.commons.domain.v2.EmptyTransaction(),
                        it.pagopa.ecommerce.commons.domain.v2.Transaction::applyEvent,
                        it.pagopa.ecommerce.commons.domain.v2.Transaction.class
                )
                .filter(t -> !(t instanceof it.pagopa.ecommerce.commons.domain.v2.EmptyTransaction))
                .cast(it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction.class)
                .zipWith(authorizationRequestedCreationDate);

        Mono<TransactionInfoDto> v1Info = transactionV1
                .flatMap(
                        t -> this.updateTransactionAuthorizationStatusV1(
                                t.getT1(),
                                updateAuthorizationRequestDto,
                                t.getT2()
                        )
                );

        Mono<TransactionInfoDto> v2Info = transactionV2
                .flatMap(
                        t -> this.updateTransactionAuthorizationStatusV2(
                                t.getT1(),
                                updateAuthorizationRequestDto,
                                t.getT2()
                        )
                );

        return v1Info
                .switchIfEmpty(v2Info)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId.value())));
    }

    private Mono<TransactionInfoDto> updateTransactionAuthorizationStatusV1(
                                                                            it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction transaction,
                                                                            UpdateAuthorizationRequestDto updateAuthorizationRequestDto,
                                                                            ZonedDateTime authorizationRequestedTime
    ) {
        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction.getTransactionId(),
                transaction.getStatus().toString(),
                updateAuthorizationRequestDto,
                authorizationRequestedTime,
                Optional.empty()
        );

        // FIXME Handle multiple rtpId
        TransactionUpdateAuthorizationCommand transactionUpdateAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                updateAuthorizationStatusData
        );

        Mono<it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction> baseTransaction = Mono.just(transaction);
        return wasTransactionAuthorized(transaction.getTransactionId())
                .<Either<TransactionInfoDto, Mono<BaseTransaction>>>flatMap(alreadyAuthorized -> {
                    if (Boolean.FALSE.equals(alreadyAuthorized)) {
                        return Mono.just(baseTransaction).map(Either::right);
                    } else {
                        return baseTransaction.map(
                                trx -> {
                                    log.info(
                                            "UpdateTransactionAuthorization Transaction authorization outcome already received. Transaction status: {}",
                                            trx.getStatus()
                                    );
                                    return buildTransactionInfoDtoV1(trx);
                                }
                        ).map(Either::left);
                    }
                })
                .flatMap(
                        either -> either.fold(
                                Mono::just,
                                tx -> baseTransaction
                                        .flatMap(
                                                t -> transactionUpdateAuthorizationHandlerV1
                                                        .handle(transactionUpdateAuthorizationCommand)
                                                        .doOnNext(
                                                                authorizationStatusUpdatedEvent -> log.info(
                                                                        "UpdateTransactionAuthorization Requested authorization update for rptId: {}",
                                                                        transaction.getPaymentNotices().get(0).rptId()
                                                                )
                                                        )
                                                        .doOnError(
                                                                AlreadyProcessedException.class,
                                                                exception -> log.error(
                                                                        "UpdateTransactionAuthorization Error: requesting authorization update for transaction in state {}",
                                                                        t.getStatus()
                                                                )
                                                        )
                                                        .cast(
                                                                it.pagopa.ecommerce.commons.documents.v1.TransactionAuthorizationCompletedEvent.class
                                                        )
                                                        .flatMap(
                                                                authorizationStatusUpdatedEvent -> authorizationUpdateProjectionHandlerV1
                                                                        .handle(authorizationStatusUpdatedEvent)
                                                        )
                                        )
                                        .cast(
                                                it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithPaymentToken.class
                                        )
                                        .flatMap(
                                                t -> closePaymentV1(
                                                        t,
                                                        updateAuthorizationRequestDto
                                                )
                                        )
                                        .map(this::buildTransactionInfoDtoV1)
                        )
                );
    }

    private Mono<TransactionInfoDto> updateTransactionAuthorizationStatusV2(
                                                                            it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction transaction,
                                                                            UpdateAuthorizationRequestDto updateAuthorizationRequestDto,
                                                                            ZonedDateTime authorizationRequestedTime
    ) {
        UpdateAuthorizationStatusData updateAuthorizationStatusData = new UpdateAuthorizationStatusData(
                transaction.getTransactionId(),
                transaction.getStatus().toString(),
                updateAuthorizationRequestDto,
                authorizationRequestedTime,
                Optional.of(transaction)
        );

        // FIXME Handle multiple rtpId
        TransactionUpdateAuthorizationCommand transactionUpdateAuthorizationCommand = new TransactionUpdateAuthorizationCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                updateAuthorizationStatusData
        );

        Mono<it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction> baseTransaction = Mono.just(transaction);
        return wasTransactionAuthorized(
                transaction.getTransactionId()
        ).<Either<TransactionInfoDto, Mono<it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction>>>flatMap(
                alreadyAuthorized -> {
                    if (Boolean.FALSE.equals(alreadyAuthorized)) {
                        return Mono.just(baseTransaction).map(Either::right);
                    } else {
                        return baseTransaction.map(
                                trx -> {
                                    log.info(
                                            "UpdateTransactionAuthorization outcome already received. Transaction status: {}",
                                            trx.getStatus()
                                    );
                                    return buildTransactionInfoDtoV2(trx);
                                }
                        ).map(Either::left);
                    }
                }
        )
                .flatMap(
                        either -> either.fold(
                                Mono::just,
                                tx -> baseTransaction
                                        .flatMap(
                                                t -> transactionUpdateAuthorizationHandlerV2
                                                        .handle(transactionUpdateAuthorizationCommand)
                                                        .doOnNext(
                                                                authorizationStatusUpdatedEvent -> log.info(
                                                                        "UpdateTransactionAuthorization requested for rptId: {}",
                                                                        transaction.getPaymentNotices().get(0).rptId()
                                                                )
                                                        )
                                                        .doOnError(
                                                                AlreadyProcessedException.class,
                                                                exception -> log.error(
                                                                        "UpdateTransactionAuthorization Error: requesting authorization update for transaction in state {}",
                                                                        t.getStatus()
                                                                )
                                                        )
                                                        .cast(
                                                                it.pagopa.ecommerce.commons.documents.v2.TransactionAuthorizationCompletedEvent.class
                                                        )
                                                        .flatMap(
                                                                authorizationUpdateProjectionHandlerV2::handle
                                                        )
                                        )

                                        .cast(
                                                it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransactionWithPaymentToken.class
                                        )
                                        .flatMap(
                                                this::closePaymentV2
                                        )
                                        .map(this::buildTransactionInfoDtoV2)
                        )
                );
    }

    private Mono<it.pagopa.ecommerce.commons.documents.v1.Transaction> closePaymentV1(
                                                                                      it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransactionWithPaymentToken transaction,
                                                                                      UpdateAuthorizationRequestDto updateAuthorizationRequestDto
    ) {
        ClosureSendData closureSendData = new ClosureSendData(
                transaction.getTransactionId(),
                updateAuthorizationRequestDto
        );

        TransactionClosureSendCommand transactionClosureSendCommand = new TransactionClosureSendCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                closureSendData
        );

        return transactionSendClosureHandlerV1
                .handle(transactionClosureSendCommand)
                .doOnNext(closureSentEvent ->
                // FIXME Handle multiple rtpId
                log.info(
                        "Requested transaction closure for rptId: {}",
                        transaction.getPaymentNotices().get(0).rptId().value()
                )
                )
                .flatMap(
                        el -> el.getT1().map(
                                refundEvent -> refundRequestProjectionHandlerV1.handle(
                                        (it.pagopa.ecommerce.commons.documents.v1.TransactionRefundRequestedEvent) refundEvent
                                )
                        ).orElse(
                                el.getT2().fold(
                                        closureErrorEvent -> closureErrorProjectionHandlerV1.handle(
                                                (it.pagopa.ecommerce.commons.documents.v1.TransactionClosureErrorEvent) closureErrorEvent
                                        ),
                                        closureDataTransactionEvent -> closureSendProjectionHandlerV1
                                                .handle(
                                                        (it.pagopa.ecommerce.commons.documents.v1.TransactionEvent<it.pagopa.ecommerce.commons.documents.v1.TransactionClosureData>) closureDataTransactionEvent
                                                )
                                )
                        )

                );
    }

    private Mono<it.pagopa.ecommerce.commons.documents.v2.Transaction> closePaymentV2(
                                                                                      it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransactionWithPaymentToken transaction
    ) {

        TransactionClosureRequestCommand transactionClosureRequestCommand = new TransactionClosureRequestCommand(
                transaction.getPaymentNotices().get(0).rptId(),
                transaction.getTransactionId()
        );

        return transactionSendClosureRequestHandler
                .handle(transactionClosureRequestCommand)
                .doOnNext(closureSentRequestedEvent ->
                // FIXME Handle multiple rtpId
                log.info(
                        "Requested async transaction closure for rptId: {}",
                        transaction.getPaymentNotices().get(0).rptId().value()
                )
                )
                .flatMap(
                        closureRequestedEvent -> closureRequestedProjectionHandler.handle(
                                (it.pagopa.ecommerce.commons.documents.v2.TransactionClosureRequestedEvent) closureRequestedEvent

                        )

                );
    }

    private TransactionInfoDto buildTransactionInfoDtoV1(
                                                         it.pagopa.ecommerce.commons.documents.v1.Transaction transactionDocument
    ) {
        return new TransactionInfoDto()
                .transactionId(
                        transactionDocument
                                .getTransactionId()
                )
                .payments(
                        transactionDocument
                                .getPaymentNotices()
                                .stream()
                                .map(
                                        paymentNotice -> new PaymentInfoDto()
                                                .amount(paymentNotice.getAmount())
                                                .reason(paymentNotice.getDescription())
                                                .paymentToken(paymentNotice.getPaymentToken())
                                                .rptId(paymentNotice.getRptId())
                                )
                                .toList()
                )
                .status(transactionsUtils.convertEnumerationV1(transactionDocument.getStatus()));

    }

    private TransactionInfoDto buildTransactionInfoDtoV2(
                                                         it.pagopa.ecommerce.commons.documents.v2.Transaction transactionDocument
    ) {
        return new TransactionInfoDto()
                .transactionId(
                        transactionDocument
                                .getTransactionId()
                )
                .payments(
                        transactionDocument
                                .getPaymentNotices()
                                .stream()
                                .map(
                                        paymentNotice -> new PaymentInfoDto()
                                                .amount(paymentNotice.getAmount())
                                                .reason(paymentNotice.getDescription())
                                                .paymentToken(paymentNotice.getPaymentToken())
                                                .rptId(paymentNotice.getRptId())
                                )
                                .toList()
                )
                .status(transactionsUtils.convertEnumerationV1(transactionDocument.getStatus()));

    }

    private TransactionInfoDto buildTransactionInfoDtoV1(
                                                         it.pagopa.ecommerce.commons.domain.v1.pojos.BaseTransaction baseTransaction
    ) {
        return new TransactionInfoDto()
                .transactionId(baseTransaction.getTransactionId().value())
                .payments(
                        baseTransaction.getPaymentNotices()
                                .stream().map(
                                        paymentNotice -> new PaymentInfoDto()
                                                .amount(paymentNotice.transactionAmount().value())
                                                .reason(paymentNotice.transactionDescription().value())
                                                .paymentToken(paymentNotice.paymentToken().value())
                                                .rptId(paymentNotice.rptId().value())
                                ).toList()
                )
                .status(transactionsUtils.convertEnumerationV1(baseTransaction.getStatus()));
    }

    private TransactionInfoDto buildTransactionInfoDtoV2(
                                                         it.pagopa.ecommerce.commons.domain.v2.pojos.BaseTransaction baseTransaction
    ) {
        return new TransactionInfoDto()
                .transactionId(baseTransaction.getTransactionId().value())
                .payments(
                        baseTransaction.getPaymentNotices()
                                .stream().map(
                                        paymentNotice -> new PaymentInfoDto()
                                                .amount(paymentNotice.transactionAmount().value())
                                                .reason(paymentNotice.transactionDescription().value())
                                                .paymentToken(paymentNotice.paymentToken().value())
                                                .rptId(paymentNotice.rptId().value())
                                ).toList()
                )
                .status(transactionsUtils.convertEnumerationV1(baseTransaction.getStatus()));

    }

    private Mono<Boolean> wasTransactionAuthorized(
                                                   TransactionId transactionId
    ) {
        /*
         * @formatter:off
         *
         * This method determines whether transaction has been previously authorized or not
         * by searching for an authorization completed event.
         * The check is performed directly on the presence of an authorization completed event
         * and not on the fact that the transaction aggregate is an instance of `BaseTransactionWithCompletedAuthorization`
         * because a generic transaction can go in the REFUNDED or EXPIRED states without undergoing authorization
         * (the corresponding aggregates do not extend, in fact, `BaseTransactionWithCompletedAuthorization`).
         *
         * This can happen, for example, when a transaction expires before getting a payment gateway response
         * (for the EXPIRED state; if in REFUNDED that means the transaction was already refunded).
         *
         * @formatter:on
         */
        return eventsRepository
                .findByTransactionIdAndEventCode(
                        transactionId.value(),
                        TransactionEventCode.TRANSACTION_AUTHORIZATION_COMPLETED_EVENT.toString()
                )
                .map(v -> true)
                .switchIfEmpty(Mono.just(false));

    }

    @Retry(name = "addUserReceipt")
    public Mono<TransactionInfoDto> addUserReceipt(
            String transactionId,
            AddUserReceiptRequestDto addUserReceiptRequest
    ) {
        return transactionsViewRepository
                .findById(transactionId)
                .switchIfEmpty(Mono.error(new TransactionNotFoundException(transactionId)))
                .map(
                        transactionDocument -> {
                            AddUserReceiptData addUserReceiptData = new AddUserReceiptData(
                                    new TransactionId(transactionId),
                                    addUserReceiptRequest
                            );
                            // FIXME Handle multiple rtpId
                            return Tuple.of(transactionDocument, new TransactionAddUserReceiptCommand(
                                    null,
                                    addUserReceiptData
                            ));
                        }
                )
                .flatMap(
                        el -> switch (el._1) {
                            case it.pagopa.ecommerce.commons.documents.v1.Transaction t ->
                                    transactionRequestUserReceiptHandlerV1
                                            .handle(el._2)
                                            .doOnNext(
                                                    transactionUserReceiptRequestedEvent -> log.info(
                                                            "{} for transactionId: {}",
                                                            TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT,
                                                            transactionUserReceiptRequestedEvent.getTransactionId()
                                                    )
                                            )
                                            .flatMap(event -> transactionUserReceiptProjectionHandlerV1
                                                    .handle((TransactionUserReceiptRequestedEvent) event))
                                            .doOnNext(
                                                    transaction -> log.info(
                                                            "Transaction status updated {} for transactionId: {}",
                                                            transaction.getStatus(),
                                                            transaction.getTransactionId()
                                                    )
                                            )
                                            .map(this::buildTransactionInfoDtoV1);

                            case Transaction t -> transactionRequestUserReceiptHandlerV2
                                    .handle(el._2)
                                    .doOnNext(
                                            transactionUserReceiptRequestedEvent -> log.info(
                                                    "{} for transactionId: {}",
                                                    TransactionEventCode.TRANSACTION_USER_RECEIPT_REQUESTED_EVENT,
                                                    transactionUserReceiptRequestedEvent.getTransactionId()
                                            )
                                    )
                                    .flatMap(event -> transactionUserReceiptProjectionHandlerV2
                                            .handle((it.pagopa.ecommerce.commons.documents.v2.TransactionUserReceiptRequestedEvent) event))
                                    .doOnNext(
                                            transaction -> log.info(
                                                    "Transaction status updated {} for transactionId: {}",
                                                    transaction.getStatus(),
                                                    transaction.getTransactionId()
                                            )
                                    )
                                    .map(this::buildTransactionInfoDtoV2);
                            default ->
                                    Mono.error(new BadGatewayException("Error while processing request unexpected transaction version type", HttpStatus.BAD_GATEWAY));
                        }
                );


    }

    private Mono<NewTransactionResponseDto> projectActivatedEventV1(
                                                                    it.pagopa.ecommerce.commons.documents.v1.TransactionActivatedEvent transactionActivatedEvent,
                                                                    String authToken
    ) {
        return transactionsActivationProjectionHandlerV1
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
                                .status(transactionsUtils.convertEnumerationV1(transaction.getStatus()))
                                // .feeTotal()//TODO da dove prendere le fees?
                                .clientId(convertClientId(transaction.getClientId().name()))
                                .idCart(transaction.getTransactionActivatedData().getIdCart())
                );
    }

    private Mono<NewTransactionResponseDto> projectActivatedEventV2(
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
                                .status(transactionsUtils.convertEnumerationV1(transaction.getStatus()))
                                // .feeTotal()//TODO da dove prendere le fees?
                                .clientId(convertClientId(transaction.getClientId().name()))
                                .idCart(transaction.getTransactionActivatedData().getIdCart())
                );
    }

    public NewTransactionResponseDto.ClientIdEnum convertClientId(
                                                                  String clientId
    ) {
        return Optional.ofNullable(clientId).filter(Objects::nonNull)
                .map(
                        value -> {
                            try {
                                return NewTransactionResponseDto.ClientIdEnum.fromValue(value);
                            } catch (IllegalArgumentException e) {
                                log.error("Unknown input origin ", e);
                                throw new InvalidRequestException("Unknown input origin", e);
                            }
                        }
                ).orElseThrow(() -> new InvalidRequestException("Null value as input origin"));
    }

    private Mono<PaymentSessionData> retrieveInformationFromAuthorizationRequest(RequestAuthorizationRequestDto requestAuthorizationRequestDto, String clientId) {
        return switch (requestAuthorizationRequestDto.getDetails()) {
            case CardAuthRequestDetailsDto cardData ->
                    Mono.just(new PaymentSessionData(cardData.getPan().substring(0, 6), null, Optional.of(cardData.getBrand()).map(Enum::toString).orElse(null), null));
            case CardsAuthRequestDetailsDto cards ->
                    ecommercePaymentMethodsClient.retrieveCardData(requestAuthorizationRequestDto.getPaymentInstrumentId(), cards.getOrderId()).map(response -> new PaymentSessionData(response.getBin(), response.getSessionId(), response.getBrand(), null));
            case WalletAuthRequestDetailsDto wallet -> walletClient
                    .getWalletInfo(wallet.getWalletId())
                    .map(walletAuthDataDto -> {
                        String bin = null;
                        if (walletAuthDataDto.getPaymentMethodData() instanceof WalletAuthCardDataDto cardsData) {
                            bin = cardsData.getBin();
                        }
                        return new PaymentSessionData(
                                bin,
                                null,
                                walletAuthDataDto.getBrand(),
                                walletAuthDataDto.getContractId());
                    });
            case ApmAuthRequestDetailsDto ignore ->
                    ecommercePaymentMethodsClient.getPaymentMethod(requestAuthorizationRequestDto.getPaymentInstrumentId(), clientId).map(response -> new PaymentSessionData(null, null, response.getName(), null));
            case RedirectionAuthRequestDetailsDto ignored -> Mono.just(new PaymentSessionData(
                    null,
                    null,
                    "N/A",//TODO handle this value for Nodo close payment
                    null
            ));
            default -> Mono.just(new PaymentSessionData(null, null, null, null));
        };
    }
}
