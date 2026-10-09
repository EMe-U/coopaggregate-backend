package com.coopaggregate.delivery;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    boolean existsByClientUuid(UUID clientUuid);

    Optional<Delivery> findByClientUuid(UUID clientUuid);

    boolean existsByReceiptCode(String receiptCode);
}
