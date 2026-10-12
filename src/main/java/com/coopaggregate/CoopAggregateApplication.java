package com.coopaggregate;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import com.coopaggregate.common.KigaliTime;

@SpringBootApplication
public class CoopAggregateApplication {

    public static void main(String[] args) {
        // Render runs in UTC. Date checks such as @PastOrPresent use the JVM time zone,
        // so without this a Kigali date between midnight and 02:00 would count as the future.
        TimeZone.setDefault(TimeZone.getTimeZone(KigaliTime.ZONE));
        SpringApplication.run(CoopAggregateApplication.class, args);
    }
}
