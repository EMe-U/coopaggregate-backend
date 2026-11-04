package com.coopaggregate.batch;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LossAllocationRepository extends JpaRepository<LossAllocation, Long> {

    List<LossAllocation> findByDeliveryIdOrderById(Long deliveryId);

    List<LossAllocation> findByLossIdOrderById(Long lossId);

    Optional<LossAllocation> findByLossIdAndDeliveryId(Long lossId, Long deliveryId);
}
