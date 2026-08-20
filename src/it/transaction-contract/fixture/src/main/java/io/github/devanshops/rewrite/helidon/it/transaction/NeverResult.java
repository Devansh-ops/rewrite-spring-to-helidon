package io.github.devanshops.rewrite.helidon.it.transaction;

public record NeverResult(TransactionSnapshot before,
                          TransactionSnapshot after,
                          boolean contextRejected) {
}
