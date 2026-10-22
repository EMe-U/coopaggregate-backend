package com.coopaggregate.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/** SMS are sent in the background after a delivery is saved, and failed ones are retried on a schedule. */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncAndSchedulingConfig {
}
