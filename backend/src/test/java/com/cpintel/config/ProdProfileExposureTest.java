package com.cpintel.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the prod profile publishes beyond the API itself.
 *
 * <p>Read from application-prod.yml directly: the point is the file that ships, and a booted
 * context would need every database this deployment talks to.
 */
class ProdProfileExposureTest {

    private static Properties prod() {
        return yaml("application-prod.yml");
    }

    private static Properties yaml(String file) {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource(file));
        return yaml.getObject();
    }

    @Test
    @DisplayName("no origin is allowed cross-origin unless the environment names one, in prod or base")
    void corsEmptyByDefault() {
        String unsetOrEmpty = "${CPINTEL_CORS_ALLOWED_ORIGINS:}";
        assertThat(prod().getProperty("cpintel.cors.allowed-origins")).isEqualTo(unsetOrEmpty);
        assertThat(yaml("application.yml").getProperty("cpintel.cors.allowed-origins"))
            .isEqualTo(unsetOrEmpty);
        // The dev and desktop profiles must not grow a list of their own.
        assertThat(yaml("application-dev.yml").getProperty("cpintel.cors.allowed-origins")).isNull();
        assertThat(yaml("application-desktop.yml").getProperty("cpintel.cors.allowed-origins")).isNull();
    }

    @Test
    @DisplayName("prod exposes health and prometheus, and not metrics")
    void actuatorExposure() {
        List<String> exposed = Arrays.stream(
                prod().getProperty("management.endpoints.web.exposure.include").split(","))
            .map(String::trim).toList();

        assertThat(exposed).containsExactlyInAnyOrder("health", "prometheus");
    }

    @Test
    @DisplayName("prod serves neither the OpenAPI description nor Swagger UI")
    void apiDocsOff() {
        Properties p = prod();
        assertThat(p.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
        assertThat(p.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
    }
}
