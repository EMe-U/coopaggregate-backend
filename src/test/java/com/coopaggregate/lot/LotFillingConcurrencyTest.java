package com.coopaggregate.lot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.coopaggregate.delivery.CorrectDeliveryRequest;
import com.coopaggregate.delivery.DeliveryRequest;
import com.coopaggregate.delivery.DeliveryService;
import com.coopaggregate.delivery.DisputeReason;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.manager.ManagerInitializer;
import com.coopaggregate.manager.ManagerRepository;
import com.coopaggregate.sale.SaleRequest;
import com.coopaggregate.sale.SaleService;
import com.coopaggregate.sms.InboundSmsService;

/**
 * Fixed-size lots under real concurrency, on a real Postgres: many confirmations of one grade at once never fill a lot
 * past its capacity, and every path that takes locks (confirmations, reversals, corrections, new deliveries, sales)
 * runs side by side without a deadlock. Mocks cannot show either.
 *
 * Needs an EMPTY local database, migrated by this test:
 *   ./mvnw test -Dtest=LotFillingConcurrencyTest -Dcoopaggregate.test.postgres-url=jdbc:postgresql://localhost:5432/x
 * (optionally -Dcoopaggregate.test.postgres-user and -Dcoopaggregate.test.postgres-password). Skipped without it, and
 * for any host other than localhost, so it can never run against a shared database.
 */
@EnabledIfSystemProperty(named = "coopaggregate.test.postgres-url", matches = ".+")
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "app.jwt.secret=test-only-not-a-real-secret-0123456789abcdef",
        "app.frontend-url=http://localhost:5173",
        "app.manager.name=x", "app.manager.email=x@example.test", "app.manager.password=x",
        "app.sms.provider=log", "app.sms.log-show-codes=false",
        "app.sms.at.username=x", "app.sms.at.api-key=x", "app.sms.at.sender-id=x", "app.sms.inbound-secret=x",
        "server.port=0"})
class LotFillingConcurrencyTest {

    private static final int THREADS = 16;
    private static final long BIG = 1L;
    private static final long NORMAL = 2L;

    @MockitoBean
    ManagerInitializer managerInitializer;

    @Autowired
    DeliveryService deliveryService;
    @Autowired
    InboundSmsService inboundSmsService;
    @Autowired
    SaleService saleService;
    @Autowired
    LedgerService ledgerService;
    @Autowired
    ManagerRepository managerRepository;
    @Autowired
    JdbcTemplate jdbc;

    private Manager manager;
    private final Random random = new Random(36);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getProperty("coopaggregate.test.postgres-url");
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", () -> System.getProperty("coopaggregate.test.postgres-user",
                "postgres"));
        registry.add("spring.datasource.password", () -> System.getProperty("coopaggregate.test.postgres-password",
                ""));
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> String.valueOf(THREADS + 4));
    }

    @BeforeEach
    void setUp() {
        String url = System.getProperty("coopaggregate.test.postgres-url");
        assumeTrue(url.contains("//localhost:") || url.contains("//127.0.0.1:"), "only a local database");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM manager", Long.class) == 0) {
            jdbc.update("INSERT INTO manager (name, email, password_hash) VALUES ('Test', 't@example.test', 'x')");
            jdbc.update("INSERT INTO buyer (name, phone) VALUES ('Buyer', '+250788999999')");
        }
        manager = managerRepository.findAll().get(0);
        // Managers confirm with a review while SMS are off; no SMS is queued.
        jdbc.update("UPDATE setting SET sms_enabled = FALSE, lot_size_kg = 100");
    }

    @Test
    void manyConfirmationsOfOneGradeAtOnceNeverFillALotPastItsCapacity() throws Exception {
        List<Long> deliveries = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            deliveries.add(record(newMember(), i % 4 == 0 ? NORMAL : BIG, kg()));
        }

        List<Callable<Object>> work = new ArrayList<>();
        for (Long id : deliveries) {
            work.add(() -> deliveryService.reviewPending(id, "Checked with the member", manager));
        }
        List<Throwable> failures = runAllAtOnce(work);

        assertEquals(List.of(), failures);
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM delivery WHERE id IN ("
                + join(deliveries) + ") AND confirmation_status <> 'CONFIRMED'", Long.class));
        assertLotsAreConsistent();
    }

    @Test
    void everyPathThatTakesLocksRunsSideBySideWithoutADeadlock() throws Exception {
        List<Callable<Object>> work = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            long grade = i % 3 == 0 ? NORMAL : BIG;
            long member = newMember();
            String phone = phoneOf(member);

            long reviewed = record(member, grade, kg());
            work.add(() -> deliveryService.reviewPending(reviewed, "Checked", manager));

            long replied = record(member, grade, kg());
            String receipt = receiptOf(replied);
            work.add(() -> inboundSmsService.handle(phone, "1 " + receipt));

            long disputed = record(member, grade, kg());
            deliveryService.dispute(disputed, DisputeReason.WRONG_KG, null);
            work.add(() -> deliveryService.resolveDispute(disputed, "Weighed again: correct"));

            long reversed = record(member, grade, kg());
            work.add(() -> deliveryService.reverse(reversed, "Recorded twice", manager));

            long corrected = record(member, grade, kg());
            work.add(() -> deliveryService.correct(corrected,
                    new CorrectDeliveryRequest("Wrong weight", member, grade, kg()), manager));

            work.add(() -> deliveryService.record(new DeliveryRequest(UUID.randomUUID(), member, grade, kg(), null),
                    manager));
            work.add(() -> saleService.record(new SaleRequest(UUID.randomUUID(), grade, 1L, new BigDecimal("5"),
                    300L, null, List.of(), null, null), manager));
        }
        Collections.shuffle(work, random);

        List<Throwable> failures = runAllAtOnce(work);

        // Sales may be refused for lack of sellable kg (stage 2 sells from lots); nothing else may fail.
        List<Throwable> unexpected = failures.stream().filter(e -> !(e instanceof BadRequestException)).toList();
        assertEquals(List.of(), unexpected.stream().map(LotFillingConcurrencyTest::describe).toList());
        assertLotsAreConsistent();
        assertTrue(ledgerService.verify().valid(), "the ledger hash chain must still verify");
    }

    // Every fixed-size lot holds at most its capacity, is FULL exactly when it holds it, and only the newest lot of a
    // grade may still be filling. Every confirmed delivery from these tests is in lots for all of its kg; a delivery
    // that was never confirmed is in none.
    private void assertLotsAreConsistent() {
        List<Map<String, Object>> lots = jdbc.queryForList("""
                SELECT l.id, l.grade_id, l.status, l.capacity_kg, COALESCE(SUM(p.quantity_kg), 0) AS filled
                FROM lot l LEFT JOIN lot_portion p ON p.lot_id = l.id
                WHERE l.capacity_kg IS NOT NULL
                GROUP BY l.id, l.grade_id, l.status, l.capacity_kg
                ORDER BY l.grade_id, l.id
                """);
        assertTrue(!lots.isEmpty());
        for (int i = 0; i < lots.size(); i++) {
            Map<String, Object> lot = lots.get(i);
            BigDecimal filled = (BigDecimal) lot.get("filled");
            BigDecimal capacity = (BigDecimal) lot.get("capacity_kg");
            String status = (String) lot.get("status");
            assertTrue(filled.compareTo(capacity) <= 0, "lot " + lot + " is over capacity");
            assertEquals(filled.compareTo(capacity) == 0, "FULL".equals(status), "lot " + lot);
            boolean newestOfGrade = i == lots.size() - 1 || !lots.get(i + 1).get("grade_id").equals(lot.get("grade_id"));
            if ("FILLING".equals(status)) {
                assertTrue(newestOfGrade, "only the newest lot of a grade may be filling: " + lot);
            }
        }
        assertEquals(0L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM (SELECT grade_id FROM lot WHERE status = 'FILLING' GROUP BY grade_id
                HAVING COUNT(*) > 1) x
                """, Long.class));
        assertEquals(0L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM delivery d
                WHERE d.lot_id IS NULL AND d.reversed_at IS NULL AND d.confirmation_status = 'CONFIRMED'
                  AND d.quantity_kg <> (SELECT COALESCE(SUM(p.quantity_kg), 0) FROM lot_portion p
                                        WHERE p.delivery_id = d.id)
                """, Long.class), "a confirmed delivery is in lots for all of its kg");
        assertEquals(0L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM delivery d JOIN lot_portion p ON p.delivery_id = d.id
                WHERE d.lot_id IS NULL AND d.confirmation_status = 'PENDING'
                """, Long.class), "a waiting delivery is in no lot");
    }

    private List<Throwable> runAllAtOnce(List<Callable<Object>> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();
        for (Callable<Object> task : work) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    task.call();
                } catch (Throwable e) {
                    failures.add(e);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();
        return new ArrayList<>(failures);
    }

    private long record(long member, long grade, BigDecimal kg) {
        return deliveryService.record(new DeliveryRequest(UUID.randomUUID(), member, grade, kg,
                Instant.now().minusSeconds(random.nextInt(3600))), manager).delivery().id();
    }

    private long newMember() {
        long count = jdbc.queryForObject("SELECT COUNT(*) FROM member", Long.class) + 1;
        String phone = String.format("+25078%07d", count);
        jdbc.update("INSERT INTO member (member_code, full_name, phone, join_date) VALUES (?, ?, ?, current_date)",
                String.format("M-%04d", count), "Member " + count, phone);
        return jdbc.queryForObject("SELECT id FROM member WHERE phone = ?", Long.class, phone);
    }

    private String phoneOf(long member) {
        return jdbc.queryForObject("SELECT phone FROM member WHERE id = ?", String.class, member);
    }

    private String receiptOf(long delivery) {
        return jdbc.queryForObject("SELECT receipt_code FROM delivery WHERE id = ?", String.class, delivery);
    }

    // Between 5 and 65 kg, in hundredths, so lots fill and split at odd places.
    private BigDecimal kg() {
        return BigDecimal.valueOf(500 + random.nextInt(6000), 2);
    }

    private static String join(List<Long> ids) {
        return String.join(",", ids.stream().map(String::valueOf).toList());
    }

    private static String describe(Throwable e) {
        StringBuilder text = new StringBuilder(e.toString());
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            text.append(" <- ").append(cause);
        }
        return text.toString();
    }
}
