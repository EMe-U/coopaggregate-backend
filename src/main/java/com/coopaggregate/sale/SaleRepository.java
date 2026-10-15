package com.coopaggregate.sale;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface SaleRepository extends JpaRepository<Sale, Long>, JpaSpecificationExecutor<Sale> {

    Optional<Sale> findByClientUuid(UUID clientUuid);

    boolean existsBySaleCode(String saleCode);

    List<Sale> findBySaleOrderIdOrderByIdAsc(Long saleOrderId);

    @Override
    @EntityGraph(attributePaths = {"lot", "buyer", "saleOrder"})
    Page<Sale> findAll(Specification<Sale> spec, Pageable pageable);

    @Query("SELECT COALESCE(SUM(s.totalAmount), 0) FROM Sale s WHERE s.saleDate BETWEEN :from AND :to")
    long totalAmountBetween(LocalDate from, LocalDate to);

    @Query("SELECT COALESCE(SUM(s.quantityKg), 0) FROM Sale s WHERE s.saleDate BETWEEN :from AND :to")
    BigDecimal totalKgBetween(LocalDate from, LocalDate to);

    @Query("SELECT COUNT(DISTINCT s.buyer.id) FROM Sale s WHERE s.saleDate BETWEEN :from AND :to")
    long buyerCountBetween(LocalDate from, LocalDate to);

    @EntityGraph(attributePaths = {"lot", "buyer"})
    List<Sale> findByIdIn(Collection<Long> ids);

    boolean existsByLotId(Long lotId);
}
