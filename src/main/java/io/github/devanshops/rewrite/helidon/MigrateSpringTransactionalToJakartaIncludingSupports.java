package io.github.devanshops.rewrite.helidon;

/**
 * Explicit opt-in variant that accepts Jakarta's lack of Spring's possible
 * resource-synchronization scope for SUPPORTS without an active transaction.
 */
public final class MigrateSpringTransactionalToJakartaIncludingSupports
        extends MigrateSpringTransactionalToJakarta {

    @Override
    public String getDisplayName() {
        return "Migrate bounded Spring transactions to Jakarta, including SUPPORTS";
    }

    @Override
    public String getDescription() {
        return "Runs the hierarchy-atomic Spring transaction migration while explicitly accepting " +
               "that Jakarta SUPPORTS does not promise Spring's non-transactional " +
               "resource-synchronization scope.";
    }

    @Override
    protected boolean includesSupports() {
        return true;
    }
}
