package io.github.devanshops.rewrite.helidon.it.transaction;

/** Deliberately rolls back the outer transaction while retaining observations. */
public final class RequiresNewRollback extends RuntimeException {
    private final RequiresNewResult result;

    public RequiresNewRollback(RequiresNewResult result) {
        super("roll back the outer REQUIRES_NEW contract transaction");
        this.result = result;
    }

    public RequiresNewResult result() {
        return result;
    }
}
