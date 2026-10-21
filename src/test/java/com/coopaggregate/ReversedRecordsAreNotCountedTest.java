package com.coopaggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.data.jpa.repository.Query;

import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.payment.ProducePaymentRepository;
import com.coopaggregate.share.LotShareRepository;

/**
 * Every query that adds up deliveries or payments must leave out reversed ones, otherwise the totals and the
 * ledger disagree. The tests cannot run JPQL without a database, so this checks the query text instead.
 */
class ReversedRecordsAreNotCountedTest {

    @ParameterizedTest(name = "{0}.{1}")
    @CsvSource({
            // repository, method, number of times the query reads deliveries or payments
            "LotRepository, totalDeliveredKg, 1",
            "LotRepository, totalPendingConfirmationKg, 1",
            "LotRepository, findQuantities, 3",
            "LotRepository, findMembers, 1",
            "LotRepository, deliveredKgBetween, 1",
            "LotRepository, deliveryCountBetween, 1",
            "LotShareRepository, findDeliveredByMember, 1",
            "LotShareRepository, findLotsOfMember, 1",
            "DeliveryRepository, findKgByGradeBetween, 1",
            "ProducePaymentRepository, findPaymentsByMember, 1",
            "ProducePaymentRepository, paidTo, 1",
            "ProducePaymentRepository, paidBetween, 1"
    })
    void countingQueryLeavesOutReversedRecords(String repository, String methodName, int expectedFilters) {
        List<Query> queries = Arrays.stream(repositoryClass(repository).getMethods())
                .filter(method -> method.getName().equals(methodName))
                .map(method -> method.getAnnotation(Query.class))
                .filter(query -> query != null)
                .toList();

        assertTrue(!queries.isEmpty(), repository + "." + methodName + " has no @Query");
        for (Query query : queries) {
            assertEquals(expectedFilters, count(query.value(), "reversedAt IS NULL"),
                    repository + "." + methodName + " must leave out reversed records: " + query.value());
        }
    }

    private static Class<?> repositoryClass(String name) {
        return switch (name) {
            case "LotRepository" -> LotRepository.class;
            case "LotShareRepository" -> LotShareRepository.class;
            case "DeliveryRepository" -> DeliveryRepository.class;
            case "ProducePaymentRepository" -> ProducePaymentRepository.class;
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }
}
