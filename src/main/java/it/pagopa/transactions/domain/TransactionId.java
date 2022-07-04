package it.pagopa.transactions.domain;

import java.util.UUID;

import it.pagopa.transactions.annotations.ValueObject;

@ValueObject
public record TransactionId(UUID value) {}
