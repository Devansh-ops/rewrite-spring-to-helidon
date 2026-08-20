package io.github.devanshops.rewrite.helidon.it.transaction;

public record RequiredJoinResult(TransactionSnapshot before,
                                 TransactionSnapshot inner,
                                 TransactionSnapshot after) {
}
