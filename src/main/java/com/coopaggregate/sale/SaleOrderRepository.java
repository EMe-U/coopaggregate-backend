package com.coopaggregate.sale;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleOrderRepository extends JpaRepository<SaleOrder, Long> {

    Optional<SaleOrder> findByClientUuid(UUID clientUuid);

    boolean existsByOrderCode(String orderCode);

    @EntityGraph(attributePaths = "buyer")
    Page<SaleOrder> findAllBy(Pageable pageable);

    @EntityGraph(attributePaths = "buyer")
    Page<SaleOrder> findByBuyerId(Long buyerId, Pageable pageable);

    @EntityGraph(attributePaths = "buyer")
    Optional<SaleOrder> findWithBuyerById(Long id);
}
