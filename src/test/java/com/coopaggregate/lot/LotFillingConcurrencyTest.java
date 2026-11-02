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
import com.coopaggregate.loss.LossReason;
import com.coopaggregate.loss.LossRequest;
import com.coopaggregate.loss.LossService;
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
    LossService lossService;
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
            work.add(() -> recordLoss(grade));

            // Deliveries already in lots: reversed, corrected, or disputed and then found correct.
            long inLots = confirmed(member, grade);
            work.add(() -> deliveryService.reverse(inLots, "Recorded for the wrong member", manager));
            long toCorrect = confirmed(member, grade);
            work.add(() -> deliveryService.correct(toCorrect,
                    new CorrectDeliveryRequest("Weighed again", member, grade, kg()), manager));
            long contested = confirmed(member, grade);
            work.add(() -> deliveryService.dispute(contested, DisputeReason.WRONG_KG, "Says it was more"));
            work.add(() -> deliveryService.resolveDispute(contested, "Scale receipt checked"));
        }
        Collections.shuffle(work, random);

        List<Throwable> failures = runAllAtOnce(work);

        // A sale or a loss may be refused for lack of kg at that moment, a delivery whose kg a sale took first can no
        // longer be reversed or corrected, and a dispute can only be resolved once it was recorded. Nothing else may
        // fail.
        List<Throwable> unexpected = failures.stream()
                .filter(e -> !(e instanceof BadRequestException))
                .filter(e -> !(e instanceof IllegalStateException && e.getMessage() != null
                        && (e.getMessage().contains("so it can no longer be reversed or corrected")
                                || e.getMessage().contains("would have less than 0 kg left")
                                || e.getMessage().contains("is not disputed"))))
                .toList();
        assertEquals(List.of(), unexpected.stream().map(LotFillingConcurrencyTest::describe).toList());
        assertLotsAreConsistent();
        assertTrue(ledgerService.verify().valid(), "the ledger hash chain must still verify");
    }

    // Every fixed-size lot holds at most its capacity, is FULL exactly when it holds it, and only the newest lot of a
    // grade may still be filling. Every confirmed delivery from these tests is in lots for all of its kg; a delivery
    // that was never confirmed is in none.
    private void assertLotsAreConsistent() {
        // placed: every portion the lot was given; filled: those of deliveries that are not reversed.
        List<Map<String, Object>> lots = jdbc.queryForList("""
                SELECT l.id, l.grade_id, l.status, l.capacity_kg, COALESCE(SUM(p.quantity_kg), 0) AS placed,
                       COALESCE(SUM(p.quantity_kg) FILTER (WHERE d.reversed_at IS NULL), 0) AS filled
                FROM lot l LEFT JOIN lot_portion p ON p.lot_id = l.id LEFT JOIN delivery d ON d.id = p.delivery_id
                WHERE l.capacity_kg IS NOT NULL
                GROUP BY l.id, l.grade_id, l.status, l.capacity_kg
                ORDER BY l.grade_id, l.id
                """);
        assertTrue(!lots.isEmpty());
        for (int i = 0; i < lots.size(); i++) {
            Map<String, Object> lot = lots.get(i);
            BigDecimal placed = (BigDecimal) lot.get("placed");
            BigDecimal filled = (BigDecimal) lot.get("filled");
            BigDecimal capacity = (BigDecimal) lot.get("capacity_kg");
            String status = (String) lot.get("status");
            assertTrue(filled.compareTo(capacity) <= 0, "lot " + lot + " is over capacity");
            // A lot being filled is not full yet (a reversal gave it space back). A FULL or SOLD lot was filled up to
            // its capacity once; a reversal since leaves it short, and it is never filled again.
            if ("FILLING".equals(status)) {
                assertTrue(filled.compareTo(capacity) < 0, "lot " + lot);
            } else {
                assertTrue(List.of("FULL", "SOLD").contains(status) && placed.compareTo(capacity) >= 0, "lot " + lot);
            }
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
        // The kg sold and lost of every delivery are those of its portions, and of its sale and loss allocations.
        assertEquals(0L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM delivery d
                WHERE d.sold_kg <> (SELECT COALESCE(SUM(p.sold_kg), 0) FROM lot_portion p WHERE p.delivery_id = d.id)
                   OR d.lost_kg <> (SELECT COALESCE(SUM(p.lost_kg), 0) FROM lot_portion p WHERE p.delivery_id = d.id)
                   OR d.sold_kg <> (SELECT COALESCE(SUM(a.quantity_kg), 0) FROM sale_allocation a
                                    WHERE a.delivery_id = d.id)
                   OR d.lost_kg <> (SELECT COALESCE(SUM(a.quantity_kg), 0) FROM loss_allocation a
                                    WHERE a.delivery_id = d.id)
                """, Long.class), "a delivery agrees with its portions and its allocations");
        // Every loss is carried in full by the portions of its lot that are not reversed, and nothing more.
        assertEquals(0L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM lot l
                WHERE (SELECT COALESCE(SUM(x.quantity_kg), 0) FROM loss x WHERE x.lot_id = l.id)
                   <> (SELECT COALESCE(SUM(p.lost_kg), 0) FROM lot_portion p JOIN delivery d ON d.id = p.delivery_id
                       WHERE p.lot_id = l.id AND d.reversed_at IS NULL)
                """, Long.class), "a lot's losses are carried by its portions");
        // Every sale's allocations add up to its kg and its amount; the credits to the amount less the deduction.
        assertEquals(0L, jdbc.queryForObject("""
                SELECT COUNT(*) FROM sale s
                WHERE s.quantity_kg <> (SELECT SUM(a.quantity_kg) FROM sale_allocation a WHERE a.sale_id = s.id)
                   OR s.total_amount <> (SELECT SUM(a.gross_rwf) FROM sale_allocation a WHERE a.sale_id = s.id)
                   OR s.total_amount <> (SELECT SUM(e.amount) FROM ledger_entry e JOIN sale_allocation a
                                         ON e.related_entity_type = 'SALE_ALLOCATION' AND e.related_entity_id = a.id
                                         WHERE a.sale_id = s.id)
                                        + (SELECT SUM(a.deduction_rwf) FROM sale_allocation a WHERE a.sale_id = s.id)
                """, Long.class), "a sale's allocations and credits add up to it");
        // Kg already sold never carry a loss: no portion holds more sold and lost kg than it received.
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM lot_portion WHERE remaining_kg < 0", Long.class));
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

    // A small loss on a lot of the grade that holds kg when the task runs. None yet: nothing to do. A lot sold out
    // between the look-up and the loss refuses it, which is expected; any other failure is not.
    private Object recordLoss(long grade) {
        List<Long> lots = jdbc.queryForList("SELECT id FROM lot WHERE grade_id = ? AND status IN ('FILLING', 'FULL')",
                Long.class, grade);
        if (lots.isEmpty()) {
            return null;
        }
        long lot;
        synchronized (random) {
            lot = lots.get(random.nextInt(lots.size()));
        }
        try {
            return lossService.record(lot, new LossRequest(new BigDecimal("0.37"), LossReason.ROT, null,
                    "Rot in the store"), manager);
        } catch (IllegalStateException e) {
            if (e.getMessage() != null && e.getMessage().contains("Losses can only be recorded")) {
                return null;
            }
            throw e;
        }
    }

    // A delivery confirmed by the manager before the work starts, so it is in lots.
    private long confirmed(long member, long grade) {
        long id = record(member, grade, kg());
        deliveryService.reviewPending(id, "Checked with the member", manager);
        return id;
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
