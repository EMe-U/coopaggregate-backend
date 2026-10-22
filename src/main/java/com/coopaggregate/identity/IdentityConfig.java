package com.coopaggregate.identity;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MosipProperties.class)
public class IdentityConfig {
}
