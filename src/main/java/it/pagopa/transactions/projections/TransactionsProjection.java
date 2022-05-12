package it.pagopa.transactions.projections;

import it.pagopa.transactions.model.RptId;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TransactionsProjection<T> {
    
    private RptId rptId;
    private T data;
}
