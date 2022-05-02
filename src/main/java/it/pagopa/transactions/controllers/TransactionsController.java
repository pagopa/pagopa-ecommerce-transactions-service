package it.pagopa.transactions.controllers;

import it.pagopa.transactions.model.IdempotencyKey;
import it.pagopa.transactions.model.RptId;
import it.pagopa.transactions.repositories.IdempotencyKeyRepository;
import it.pagopa.transactions.server.api.TransactionsApi;
import it.pagopa.transactions.server.model.BeneficiaryDto;
import it.pagopa.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.transactions.server.model.NewTransactionResponseDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import java.util.Random;

@RestController
public class TransactionsController implements TransactionsApi {
    private static final String ALPHANUMERICS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Random RANDOM = new Random();
    private static final String PSP_PAGOPA_ECOMMERCE_FISCAL_CODE = "0000000000";

    private final IdempotencyKeyRepository repository;

    @Autowired
    public TransactionsController(IdempotencyKeyRepository repository) {
        this.repository = repository;
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
        IdempotencyKey idempotencyKey = repository
                .findById(rptId)
                .orElseGet(() -> {
                    final IdempotencyKey key = new IdempotencyKey(
                            rptId,
                            PSP_PAGOPA_ECOMMERCE_FISCAL_CODE,
                            randomString(11)
                    );
                    repository.save(key);
                    return key;
                });

        NewTransactionResponseDto response = new NewTransactionResponseDto()
                .amount(0)
                .reason("")
                .beneficiary(new BeneficiaryDto());
        return ResponseEntity.ok(response);
    }
}
