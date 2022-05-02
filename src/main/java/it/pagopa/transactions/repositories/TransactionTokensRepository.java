package it.pagopa.transactions.repositories;

import it.pagopa.transactions.model.RptId;
import org.springframework.data.repository.CrudRepository;

public interface TransactionTokensRepository extends CrudRepository<TransactionTokens, RptId> {}
