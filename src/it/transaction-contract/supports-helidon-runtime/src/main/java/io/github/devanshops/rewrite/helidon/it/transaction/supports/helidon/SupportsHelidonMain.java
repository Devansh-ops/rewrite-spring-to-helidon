package io.github.devanshops.rewrite.helidon.it.transaction.supports.helidon;

import io.github.devanshops.rewrite.helidon.it.transaction.supports.SupportsContractRunner;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;

import java.nio.file.Path;

public final class SupportsHelidonMain {
    private SupportsHelidonMain() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("mp.initializer.allow", "true");
        Path output = Path.of(requiredProperty("contract.output"));
        try (SeContainer container = SeContainerInitializer.newInstance().initialize()) {
            container.select(SupportsContractRunner.class).get().run(output);
        }
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing system property: " + name);
        }
        return value;
    }
}
