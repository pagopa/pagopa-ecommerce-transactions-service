package it.pagopa.transactions.domain;

import it.pagopa.transactions.annotations.ValueObject;

@ValueObject
public record TransactionId(String value) {}
