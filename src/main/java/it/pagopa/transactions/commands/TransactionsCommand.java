package it.pagopa.transactions.commands;

import it.pagopa.transactions.model.RptId;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionsCommand<T> {

    private RptId rptId;
    private TransactionsCommandCode code;
    private T data;
}
