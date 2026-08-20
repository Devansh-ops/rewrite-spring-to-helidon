package io.github.devanshops.rewrite.helidon.it.transaction.helidon;

import io.github.devanshops.rewrite.helidon.it.transaction.RequiredContractRunner;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.se.SeContainerInitializer;

import java.nio.file.Path;

public final class HelidonRuntimeMain {
    private HelidonRuntimeMain() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("mp.initializer.allow", "true");
        Path output = Path.of(requiredProperty("contract.output"));
        try (SeContainer container = SeContainerInitializer.newInstance().initialize()) {
            container.select(RequiredContractRunner.class).get().run(output);
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
