package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LotPortionRepository extends JpaRepository<LotPortion, Long> {

    boolean existsByDeliveryId(Long deliveryId);

    List<LotPortion> findByDeliveryIdOrderById(Long deliveryId);

    // The kg already in a lot: every portion it was given, also of deliveries reversed since (their kg leave a
    // shortfall; a lot is never refilled).
    @Query("SELECT COALESCE(SUM(p.quantityKg), 0) FROM LotPortion p WHERE p.lot.id = :lotId")
    BigDecimal filledKg(Long lotId);
}
