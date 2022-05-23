package it.pagopa.transactions.model;

import it.pagopa.transactions.annotations.ValueObject;

@ValueObject
public record TransactionDescription(String value) {}
