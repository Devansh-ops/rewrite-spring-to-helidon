package io.github.devanshops.rewrite.helidon.it.transaction.supports;

public record SupportsJoinResult(SupportsSnapshot before,
                                 SupportsSnapshot inner,
                                 SupportsSnapshot after) {
}
