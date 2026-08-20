package io.github.devanshops.rewrite.helidon.it.transaction;

public record RequiresNewResult(TransactionSnapshot outerBefore,
                                TransactionSnapshot inner,
                                TransactionSnapshot outerAfter) {
}
