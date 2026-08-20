package io.github.devanshops.rewrite.helidon.it.transaction;

class ContractCheckedException extends Exception {
}

class ContractRuntimeException extends RuntimeException {
}

class ContractError extends Error {
}

class RuleParentException extends Exception {
}

class RuleNegativeChildException extends RuleParentException {
}

class RulePositiveSiblingException extends RuleParentException {
}
