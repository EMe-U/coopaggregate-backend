package com.coopaggregate.sale;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleOrderRepository extends JpaRepository<SaleOrder, Long> {

    Optional<SaleOrder> findByClientUuid(UUID clientUuid);

    boolean existsByOrderCode(String orderCode);
}
