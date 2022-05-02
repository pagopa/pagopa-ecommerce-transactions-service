package it.pagopa.transactions.controllers;

import it.pagopa.nodeforpsp.ActivatePaymentNoticeReq;
import it.pagopa.nodeforpsp.ActivatePaymentNoticeRes;
import it.pagopa.nodeforpsp.CtQrCode;
import it.pagopa.nodeforpsp.ObjectFactory;
import it.pagopa.transactions.client.NodeForPspClient;
import it.pagopa.transactions.model.IdempotencyKey;
import it.pagopa.transactions.model.RptId;
import it.pagopa.transactions.repositories.IdempotencyKeyRepository;
import it.pagopa.transactions.repositories.TransactionTokens;
import it.pagopa.transactions.repositories.TransactionTokensRepository;
import it.pagopa.transactions.server.api.TransactionsApi;
import it.pagopa.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.transactions.server.model.NewTransactionResponseDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Random;

@RestController
public class TransactionsController implements TransactionsApi {
    private static final String ALPHANUMERICS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Random RANDOM = new Random();
    private static final String PSP_PAGOPA_ECOMMERCE_FISCAL_CODE = "00000000000";

    private final TransactionTokensRepository transactionTokensRepository;
    private final ObjectFactory objectFactory;
    private final NodeForPspClient nodeForPspClient;

    @Autowired
    public TransactionsController(
            TransactionTokensRepository transactionTokensRepository,
            ObjectFactory objectFactory,
            NodeForPspClient nodeForPspClient) {
        this.transactionTokensRepository = transactionTokensRepository;
        this.objectFactory = objectFactory;
        this.nodeForPspClient = nodeForPspClient;
    }

    private String randomString(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHANUMERICS.charAt(RANDOM.nextInt(ALPHANUMERICS.length())));
        }
        return sb.toString();
    }

    @Override
    public ResponseEntity<NewTransactionResponseDto> newTransaction(NewTransactionRequestDto newTransactionRequestDto) {
        RptId rptId = new RptId(newTransactionRequestDto.getRptId());
        TransactionTokens transactionTokens = transactionTokensRepository
                .findById(rptId)
                .orElseGet(() -> {
                    System.out.println("Creating new idempotency key!");
                    final IdempotencyKey key = new IdempotencyKey(
                            rptId,
                            PSP_PAGOPA_ECOMMERCE_FISCAL_CODE,
                            randomString(10)
                    );
                    final TransactionTokens tokens = new TransactionTokens(rptId, key, null);
                    transactionTokensRepository.save(tokens);
                    return tokens;
                });

        System.out.println("Transaction tokens for " + rptId + ": " + transactionTokens);
        String fiscalCode = rptId.getFiscalCode();
        String noticeId = rptId.getNoticeId();

        CtQrCode qrCode = new CtQrCode();
        qrCode.setFiscalCode(fiscalCode);
        qrCode.setNoticeNumber(noticeId);

        BigDecimal amount = BigDecimal.valueOf(1200);

        ActivatePaymentNoticeReq request = objectFactory.createActivatePaymentNoticeReq();
        request.setAmount(amount);
        request.setQrCode(qrCode);

        // ActivatePaymentNoticeRes activatePaymentNoticeRes = nodeForPspClient.activatePaymentNotice(objectFactory.createActivatePaymentNoticeReq(request));

//        TransactionTokens tokens = new TransactionTokens(rptId, idempotencyKey, activatePaymentNoticeRes.getPaymentToken());

        NewTransactionResponseDto response = new NewTransactionResponseDto();
                //.amount(activatePaymentNoticeRes.getTotalAmount().intValue())
                //.reason(activatePaymentNoticeRes.getPaymentDescription());

        return ResponseEntity.ok(response);
    }
}
