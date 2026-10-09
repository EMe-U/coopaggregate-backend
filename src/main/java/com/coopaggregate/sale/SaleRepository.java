package com.coopaggregate.sale;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface SaleRepository extends JpaRepository<Sale, Long>, JpaSpecificationExecutor<Sale> {

    Optional<Sale> findByClientUuid(UUID clientUuid);

    boolean existsBySaleCode(String saleCode);

    @Override
    @EntityGraph(attributePaths = {"lot", "buyer"})
    Page<Sale> findAll(Specification<Sale> spec, Pageable pageable);
}
