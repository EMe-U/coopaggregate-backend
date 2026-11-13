package com.coopaggregate.sale;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.coopaggregate.payment.PaymentMethod;

public interface SaleOrderRepository extends JpaRepository<SaleOrder, Long> {

    Optional<SaleOrder> findByClientUuid(UUID clientUuid);

    boolean existsByOrderCode(String orderCode);

    // Held until the transaction ends. Selecting 1 avoids mapping Postgres's void return type.
    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:namespace, hashtext(:key))", nativeQuery = true)
    Integer lockReference(int namespace, String key);

    @Query("SELECT o.orderCode FROM SaleOrder o WHERE o.paymentMethod = :method "
            + "AND LOWER(o.paymentReference) = LOWER(:reference) ORDER BY o.id")
    List<String> findCodesUsingReference(PaymentMethod method, String reference);

    @EntityGraph(attributePaths = "buyer")
    Page<SaleOrder> findAllBy(Pageable pageable);

    @EntityGraph(attributePaths = "buyer")
    Page<SaleOrder> findByBuyerId(Long buyerId, Pageable pageable);

    @EntityGraph(attributePaths = "buyer")
    Optional<SaleOrder> findWithBuyerById(Long id);
}
