package com.coopaggregate.identity;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(MosipProperties.class)
public class IdentityConfig {

    private static final Logger log = LoggerFactory.getLogger(IdentityConfig.class);

    /**
     * Only exists when MOSIP_ENABLED=true. Refuses to start rather than fail on the first member, and names only the
     * settings that are wrong, never their values.
     */
    @Bean
    @ConditionalOnProperty(name = "app.identity.mosip.enabled", havingValue = "true")
    MosipSigningKey mosipSigningKey(MosipProperties properties) {
        List<String> problems = new ArrayList<>();
        if (!properties.missingRequired().isEmpty()) {
            problems.add("these settings are missing: " + String.join(", ", properties.missingRequired()));
        }
        if (!properties.insecure().isEmpty()) {
            problems.add("these must use https (plain http is only allowed for localhost): "
                    + String.join(", ", properties.insecure()));
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("MOSIP_ENABLED=true but " + String.join("; ", problems)
                    + ". Set them as environment variables, or set MOSIP_ENABLED=false.");
        }
        if (!properties.extraScopes().isEmpty()) {
            log.warn("MOSIP_SCOPE asks eSignet for more than the name and phone ({}); they are never used. Set "
                    + "MOSIP_SCOPE=openid or leave it out.", String.join(", ", properties.extraScopes()));
        }
        return new MosipSigningKey(MosipKeyLoader.load(properties.privateKeyPath()), properties.keyId().trim());
    }

    // The timeouts come from spring.http.client.* in application.properties, like for the SMS provider.
    @Bean
    @ConditionalOnProperty(name = "app.identity.mosip.enabled", havingValue = "true")
    MosipClient mosipClient(MosipProperties properties, MosipSigningKey signingKey, RestClient.Builder restClientBuilder,
                            RestTemplateBuilder restTemplateBuilder) {
        return new MosipClient(properties, signingKey, restClientBuilder.build(), restTemplateBuilder.build(),
                Clock.systemUTC());
    }
}
