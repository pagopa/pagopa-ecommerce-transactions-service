package it.pagopa.transactions.services;


import it.pagopa.transactions.commands.TransactionsCommand;
import it.pagopa.transactions.commands.TransactionsCommandCode;
import it.pagopa.transactions.commands.handlers.TransactionInizializeHandler;
import it.pagopa.transactions.model.RptId;
import it.pagopa.transactions.projections.handlers.TransactionsProjectionHandler;
import it.pagopa.transactions.server.model.NewTransactionRequestDto;
import it.pagopa.transactions.server.model.NewTransactionResponseDto;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class TransactionsService {

        @Autowired
        private TransactionInizializeHandler transactionInizializeHandler;

        @Autowired
        private TransactionsProjectionHandler transactionsProjectionHandler;

        public NewTransactionResponseDto newTransaction(NewTransactionRequestDto newTransactionRequestDto) {

                log.info("Transactions initialization for rptId: {}",
                                newTransactionRequestDto.getRptId());
                TransactionsCommand<NewTransactionRequestDto> command = new TransactionsCommand<>();
                command.setCode(TransactionsCommandCode.INITIALIZE_TRANSACTION);
                command.setData(newTransactionRequestDto);
                command.setRptId(new RptId(newTransactionRequestDto.getRptId()));

                NewTransactionResponseDto response = transactionInizializeHandler.handle(command);

                log.info("Transactions initialize for rptId: {}",
                                newTransactionRequestDto.getRptId());

                transactionsProjectionHandler.handle(response);

                return response;
        }
}
