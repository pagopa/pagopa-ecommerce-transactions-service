package it.pagopa.transactions.repositories;

import it.pagopa.transactions.model.IdempotencyKey;
import it.pagopa.transactions.model.RptId;
import org.springframework.data.repository.CrudRepository;

public interface IdempotencyKeyRepository extends CrudRepository<IdempotencyKey, RptId> {}
