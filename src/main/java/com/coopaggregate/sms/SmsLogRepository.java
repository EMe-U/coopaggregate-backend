package com.coopaggregate.sms;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SmsLogRepository extends JpaRepository<SmsLog, Long> {

    boolean existsByDeliveryId(Long deliveryId);

    List<SmsLog> findTop50ByStatusAndAttemptsLessThanOrderByIdAsc(SmsStatus status, int attempts);

    List<SmsLog> findTop50ByStatusAndCreatedAtBeforeOrderByIdAsc(SmsStatus status, Instant before);
}
