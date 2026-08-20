package io.github.devanshops.rewrite.helidon.it.transaction.spring;

import io.github.devanshops.rewrite.helidon.it.transaction.RequiredContractRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

public final class SpringRuntimeMain {
    private SpringRuntimeMain() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(requiredProperty("contract.output"));
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                SpringContractApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run()) {
            context.getBean(RequiredContractRunner.class).run(output);
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
