package it.pagopa.transactions.projections.handlers;

public interface ProjectionHandler<T, S> {

  S handle(T data);
}
