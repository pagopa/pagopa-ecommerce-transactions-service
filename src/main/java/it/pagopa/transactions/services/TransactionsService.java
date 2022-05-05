package it.pagopa.transactions.services;

import it.pagopa.ecommerce.sessions.v1.dto.SessionDataDto;
import it.pagopa.ecommerce.sessions.v1.dto.SessionTokenDto;
import it.pagopa.nodeforpsp.ActivatePaymentNoticeReq;
import it.pagopa.nodeforpsp.ActivatePaymentNoticeRes;
import it.pagopa.nodeforpsp.CtQrCode;
import it.pagopa.nodeforpsp.ObjectFactory;
import it.pagopa.transactions.client.EcommerceSessionsClient;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.documents.TransactionEvent;
import it.pagopa.transactions.documents.TransactionInitData;
import it.pagopa.transactions.handlers.impl.TransactionInitEventHandler;
import it.pagopa.transactions.model.IdempotencyKey;
import it.pagopa.transactions.model.RptId;
import it.pagopa.transactions.repositories.TransactionTokens;
import it.pagopa.transactions.repositories.TransactionTokensRepository;
import it.pagopa.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.transactions.server.model.NewTransactionResponseDto;
import it.pagopa.transactions.utils.TransactionEventCode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Random;

@Service
public class TransactionsService {
        private static final String ALPHANUMERICS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        private static final Random RANDOM = new Random();
        private static final String PSP_PAGOPA_ECOMMERCE_FISCAL_CODE = "00000000000";

        private final Logger logger = LoggerFactory.getLogger(TransactionsService.class);

        @Autowired
        private TransactionTokensRepository transactionTokensRepository;

        @Autowired
        private ObjectFactory objectFactory;

        @Autowired
        private NodeForPspClient nodeForPspClient;

        @Autowired
        private TransactionInitEventHandler transactionInitEventHandler;

        @Autowired
        private EcommerceSessionsClient ecommerceSessionsClient;

        public NewTransactionResponseDto newTransaction(NewTransactionRequestDto newTransactionRequestDto) {
                RptId rptId = new RptId(newTransactionRequestDto.getRptId());
                TransactionTokens transactionTokens = transactionTokensRepository
                                .findById(rptId)
                                .orElseGet(() -> {
                                        logger.info("Creating new idempotency key for rptId {}", rptId);
                                        final IdempotencyKey key = new IdempotencyKey(
                                                        PSP_PAGOPA_ECOMMERCE_FISCAL_CODE,
                                                        randomString(10));
                                        final TransactionTokens tokens = new TransactionTokens(rptId, key, null);
                                        transactionTokensRepository.save(tokens);
                                        return tokens;
                                });

                logger.info("Transaction tokens for " + rptId + ": " + transactionTokens);
                String fiscalCode = rptId.getFiscalCode();
                String noticeId = rptId.getNoticeId();

                CtQrCode qrCode = new CtQrCode();
                qrCode.setFiscalCode(fiscalCode);
                qrCode.setNoticeNumber(noticeId);

                BigDecimal amount = BigDecimal.valueOf(1200);

                ActivatePaymentNoticeReq request = objectFactory.createActivatePaymentNoticeReq();
                request.setAmount(amount);
                request.setQrCode(qrCode);
                request.setIdPSP("6666");
                request.setIdChannel("7777");
                request.setIdBrokerPSP("8888");
                request.setPassword("password");
                request.setIdempotencyKey(transactionTokens.idempotencyKey().getKey());
                request.setPaymentNote(newTransactionRequestDto.getRptId());
                ActivatePaymentNoticeRes activatePaymentNoticeRes = (ActivatePaymentNoticeRes) nodeForPspClient
                                .activatePaymentNotice(objectFactory.createActivatePaymentNoticeReq(request)).block();

                TransactionTokens tokens = new TransactionTokens(rptId, transactionTokens.idempotencyKey(),
                                activatePaymentNoticeRes.getPaymentToken());
                transactionTokensRepository.save(tokens);

                NewTransactionResponseDto response = new NewTransactionResponseDto()
                                .amount(activatePaymentNoticeRes.getTotalAmount().intValue())
                                .reason(activatePaymentNoticeRes.getPaymentDescription());

                TransactionInitData data = new TransactionInitData();
                data.setAmount(activatePaymentNoticeRes.getTotalAmount().intValue());
                data.setDescription(activatePaymentNoticeRes.getPaymentDescription());
                TransactionEvent<TransactionInitData> transactionInitializedEvent = new TransactionEvent<TransactionInitData>(
                                newTransactionRequestDto.getRptId(), activatePaymentNoticeRes.getPaymentToken(),
                                TransactionEventCode.TRANSACTION_INITIALIZED_EVENT, data);

                SessionDataDto sessionRequest = new SessionDataDto();
                sessionRequest.setEmail("test@test.it");
                sessionRequest.setPaymentToken(activatePaymentNoticeRes.getPaymentToken());
                sessionRequest.setRptId(newTransactionRequestDto.getRptId());

                SessionTokenDto sessionToken = ecommerceSessionsClient.createSessionToken(sessionRequest).block();
                transactionInitEventHandler.handle(transactionInitializedEvent);
                response.setAuthToken(sessionToken.getToken());
                response.setPaymentToken(activatePaymentNoticeRes.getPaymentToken());
                return response;
        }

        private String randomString(int len) {
                StringBuilder sb = new StringBuilder(len);
                for (int i = 0; i < len; i++) {
                        sb.append(ALPHANUMERICS.charAt(RANDOM.nextInt(ALPHANUMERICS.length())));
                }
                return sb.toString();
        }
}
