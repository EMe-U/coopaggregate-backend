package com.coopaggregate.delivery;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryRepository extends JpaRepository<Delivery, Long> {

    boolean existsByClientUuid(UUID clientUuid);
}
