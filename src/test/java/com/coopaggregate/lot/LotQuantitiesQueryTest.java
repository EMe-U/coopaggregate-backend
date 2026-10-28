package com.coopaggregate.lot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * A sale by grade has no lot of its own, so a lot's sold kg must come from what sales took from its batches. If this
 * summed the sale rows instead, a loss would pass the remaining-kg check for kg that are already sold. The query text
 * is checked because JPQL cannot run without a database.
 */
class LotQuantitiesQueryTest {

    @Test
    void soldKgAndAmountOfALotComeFromItsBatchAllocations() throws Exception {
        String query = Arrays.stream(LotRepository.class.getMethods())
                .filter(method -> method.getName().equals("findQuantities"))
                .map(method -> method.getAnnotation(Query.class).value())
                .findFirst()
                .orElseThrow();

        assertTrue(query.contains("SELECT SUM(a.quantityKg) FROM SaleAllocation a WHERE a.delivery.lot.id = l.id"));
        assertFalse(query.contains("SUM(s.quantityKg)"), "sold kg must not be summed from the sale rows");
        // Sales made before sales by grade keep their amount; credited kg add theirs.
        assertTrue(query.contains("SELECT SUM(s2.totalAmount) FROM Sale s2 WHERE s2.lot.id = l.id"));
        assertTrue(query.contains("SELECT SUM(a2.grossRwf) FROM SaleAllocation a2 WHERE a2.delivery.lot.id = l.id"));
    }
}
