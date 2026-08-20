package io.github.devanshops.rewrite.helidon.it.transaction.supports.spring;

import io.github.devanshops.rewrite.helidon.it.transaction.supports.SupportsContractRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

public final class SupportsSpringMain {
    private SupportsSpringMain() {
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(requiredProperty("contract.output"));
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                SupportsSpringApplication.class)
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .run()) {
            context.getBean(SupportsContractRunner.class).run(output);
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
