package com.coopaggregate.sale;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleRepository extends JpaRepository<Sale, Long> {

    Optional<Sale> findByClientUuid(UUID clientUuid);

    boolean existsBySaleCode(String saleCode);
}
