package com.coopaggregate.lot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * A sale by grade has no lot of its own, so a lot's kg must come from its portions: what sales and losses took from
 * them. If this summed the sale rows instead, the lot would show kg that are already sold. The query text is checked
 * because JPQL cannot run without a database.
 */
class LotQuantitiesQueryTest {

    @Test
    void theKgOfALotComeFromItsPortionsAndItsAmountFromItsAllocations() throws Exception {
        String query = Arrays.stream(LotRepository.class.getMethods())
                .filter(method -> method.getName().equals("findQuantities"))
                .map(method -> method.getAnnotation(Query.class).value())
                .findFirst()
                .orElseThrow();

        assertTrue(query.contains("SELECT SUM(p.quantityKg) FROM LotPortion p WHERE p.lot.id = l.id"));
        assertTrue(query.contains("SELECT SUM(p3.soldKg) FROM LotPortion p3 WHERE p3.lot.id = l.id"));
        assertTrue(query.contains("SELECT SUM(p2.lostKg) FROM LotPortion p2 WHERE p2.lot.id = l.id"));
        assertFalse(query.contains("SUM(s.quantityKg)"), "sold kg must not be summed from the sale rows");
        // Sales made before sales by grade keep their amount; credited portions add theirs, and the credits from
        // before fixed-size lots (no portion) are found through their delivery's own lot.
        assertTrue(query.contains("SELECT SUM(s2.totalAmount) FROM Sale s2 WHERE s2.lot.id = l.id"));
        assertTrue(query.contains("SELECT SUM(a.grossRwf) FROM SaleAllocation a WHERE a.lotPortion.lot.id = l.id"));
        assertTrue(query.contains("WHERE a2.lotPortion IS NULL AND a2.delivery.lot.id = l.id"));
    }
}
