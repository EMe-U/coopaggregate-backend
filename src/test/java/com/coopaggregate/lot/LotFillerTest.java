package com.coopaggregate.lot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.delivery.ConfirmationMethod;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DisputeReason;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.ledger.LedgerEntry;
import com.coopaggregate.member.Member;
import com.coopaggregate.setting.Setting;
import com.coopaggregate.setting.SettingRepository;

/** The repositories are small in-memory stand-ins, so several confirmations in a row behave as in the database. */
class LotFillerTest {

    private final List<Lot> lots = new ArrayList<>();
    private final List<LotPortion> portions = new ArrayList<>();
    private LotRepository lotRepository;
    private Setting setting;
    private Grade big;
    private Grade normal;
    private long nextDeliveryId = 1;
    private LotFiller filler;

    @BeforeEach
    void setUp() {
        lotRepository = mock(LotRepository.class);
        LotPortionRepository portionRepository = mock(LotPortionRepository.class);
        SettingRepository settingRepository = mock(SettingRepository.class);
        setting = new Setting();
        when(settingRepository.findById(1L)).thenReturn(Optional.of(setting));

        when(lotRepository.save(any(Lot.class))).thenAnswer(invocation -> {
            Lot lot = invocation.getArgument(0);
            ReflectionTestUtils.setField(lot, "id", (long) lots.size() + 10);
            lots.add(lot);
            return lot;
        });
        when(lotRepository.findFillingForUpdate(anyLong())).thenAnswer(invocation -> lots.stream()
                .filter(lot -> lot.getGrade().getId().equals(invocation.getArgument(0))
                        && lot.getStatus() == LotStatus.FILLING)
                .findFirst());
        when(lotRepository.countByLotCodeStartingWith(anyString())).thenAnswer(invocation -> lots.stream()
                .filter(lot -> lot.getLotCode().startsWith(invocation.getArgument(0)))
                .count());
        when(portionRepository.save(any(LotPortion.class))).thenAnswer(invocation -> {
            portions.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(portionRepository.existsByDeliveryId(anyLong())).thenAnswer(invocation -> portions.stream()
                .anyMatch(portion -> portion.getDelivery().getId().equals(invocation.getArgument(0))));
        when(portionRepository.filledKg(anyLong())).thenAnswer(invocation -> portions.stream()
                .filter(portion -> portion.getLot().getId().equals(invocation.getArgument(0)))
                .map(LotPortion::getQuantityKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        big = grade(1L, "A", "Big");
        normal = grade(2L, "B", "Normal");
        filler = new LotFiller(lotRepository, portionRepository, settingRepository);
    }

    @Test
    void theFirstConfirmedDeliveryOfAGradeStartsALotOfTheSetSize() {
        filler.place(confirmed(big, "30"));

        assertEquals(1, lots.size());
        Lot lot = lots.get(0);
        assertEquals("A-" + KigaliTime.today().getYear() + "-01", lot.getLotCode());
        assertEquals(LotStatus.FILLING, lot.getStatus());
        assertEquals(0, lot.getCapacityKg().compareTo(new BigDecimal("100")));
        assertEquals(KigaliTime.today(), lot.getOpenDate());
        assertNull(lot.getCloseDate());
        assertPortions("A-01:30.00");
    }

    @Test
    void deliveriesOfDifferentMembersShareTheLotBeingFilled() {
        filler.place(confirmed(big, "30"));
        filler.place(confirmed(big, "45.50"));

        assertEquals(1, lots.size());
        assertPortions("A-01:30.00", "A-01:45.50");
        assertEquals(2, portions.stream().map(portion -> portion.getDelivery().getMember()).distinct().count());
    }

    @Test
    void aDeliveryThatFitsExactlyFillsTheLotAndStartsNoOther() {
        filler.place(confirmed(big, "60"));
        filler.place(confirmed(big, "40"));

        assertEquals(1, lots.size());
        assertEquals(LotStatus.FULL, lots.get(0).getStatus());
        assertEquals(KigaliTime.today(), lots.get(0).getCloseDate());
    }

    @Test
    void aDeliveryThatDoesNotFitCompletesTheLotAndTheRestStartsTheNext() {
        filler.place(confirmed(big, "70"));
        Delivery split = confirmed(big, "50");

        List<LotPortion> placed = filler.place(split);

        assertEquals(2, placed.size());
        assertPortions("A-01:70.00", "A-01:30.00", "A-02:20.00");
        assertEquals(LotStatus.FULL, lots.get(0).getStatus());
        assertEquals(LotStatus.FILLING, lots.get(1).getStatus());
        assertTrue(placed.stream().allMatch(portion -> portion.getDelivery() == split));
    }

    @Test
    void aDeliveryLargerThanSeveralLotsFillsThemOneAfterTheOther() {
        filler.place(confirmed(big, "250.75"));

        assertPortions("A-01:100.00", "A-02:100.00", "A-03:50.75");
        assertEquals(List.of(LotStatus.FULL, LotStatus.FULL, LotStatus.FILLING),
                lots.stream().map(Lot::getStatus).toList());
    }

    @Test
    void aDeliveryOfExactlyTwoLotsLeavesNoLotBeingFilled() {
        filler.place(confirmed(big, "200"));

        assertEquals(List.of(LotStatus.FULL, LotStatus.FULL), lots.stream().map(Lot::getStatus).toList());
        assertTrue(lotRepository.findFillingForUpdate(1L).isEmpty());
    }

    @Test
    void eachGradeHasItsOwnLotBeingFilled() {
        filler.place(confirmed(big, "60"));
        filler.place(confirmed(normal, "60"));
        filler.place(confirmed(big, "60"));

        assertPortions("A-01:60.00", "B-01:60.00", "A-01:40.00", "A-02:20.00");
        assertEquals(1, lots.stream().filter(lot -> lot.getStatus() == LotStatus.FILLING
                && lot.getGrade() == big).count());
        assertEquals(1, lots.stream().filter(lot -> lot.getStatus() == LotStatus.FILLING
                && lot.getGrade() == normal).count());
    }

    @Test
    void aLotKeepsItsOwnCapacityAndOnlyNewLotsTakeTheSetting() {
        filler.place(confirmed(big, "30"));
        setting.setLotSizeKg(new BigDecimal("50"));

        filler.place(confirmed(big, "90"));

        assertPortions("A-01:30.00", "A-01:70.00", "A-02:20.00");
        assertEquals(0, lots.get(1).getCapacityKg().compareTo(new BigDecimal("50")));
    }

    @Test
    void hundredthsOfAKiloAreKeptExactly() {
        filler.place(confirmed(big, "33.33"));
        filler.place(confirmed(big, "33.33"));
        filler.place(confirmed(big, "33.35"));

        assertPortions("A-01:33.33", "A-01:33.33", "A-01:33.34", "A-02:0.01");
        assertEquals(LotStatus.FULL, lots.get(0).getStatus());
    }

    @Test
    void aDeliveryConfirmedLateGoesIntoTheLotBeingFilledThen() {
        Delivery earlier = confirmed(big, "40");
        ReflectionTestUtils.setField(earlier, "deliveredAt", Instant.parse("2026-10-01T08:00:00Z"));
        Delivery later = confirmed(big, "80");
        ReflectionTestUtils.setField(later, "deliveredAt", Instant.parse("2026-10-02T08:00:00Z"));

        filler.place(later);
        filler.place(earlier);

        // Lots fill in the order deliveries are confirmed; nothing moves between lots afterwards.
        assertPortions("A-01:80.00", "A-01:20.00", "A-02:20.00");
    }

    @Test
    void aDeliveryAlreadyInLotsIsNotPlacedAgain() {
        Delivery delivery = confirmed(big, "50");
        filler.place(delivery);

        assertEquals(List.of(), filler.place(delivery));
        assertPortions("A-01:50.00");
        assertTrue(filler.isInLots(delivery));
    }

    @Test
    void onlyAConfirmedDeliveryGoesIntoALot() {
        Delivery pending = delivery(big, "50");
        Delivery disputed = delivery(big, "50");
        disputed.dispute(DisputeReason.WRONG_KG, null, Instant.now());
        Delivery reversed = confirmed(big, "50");
        LedgerEntry reversal = new LedgerEntry();
        reversal.setEntryDate(Instant.now());
        reversed.markReversed(reversal);

        for (Delivery delivery : List.of(pending, disputed, reversed)) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> filler.place(delivery));
            assertEquals("Only a confirmed delivery goes into a lot.", e.getMessage());
        }
        assertTrue(portions.isEmpty());
        verify(lotRepository, never()).save(any());
    }

    @Test
    void lotCodesContinueAfterTheLotsOfBeforeFixedSizeLots() {
        Lot old = new Lot();
        old.setLotCode("A-" + KigaliTime.today().getYear() + "-01");
        old.setGrade(big);
        old.setStatus(LotStatus.OPEN);
        lotRepository.save(old);

        filler.place(confirmed(big, "10"));

        assertEquals("A-" + KigaliTime.today().getYear() + "-02", lots.get(1).getLotCode());
        // An old OPEN lot is never filled.
        assertEquals(List.of(), portions.stream().filter(portion -> portion.getLot() == old).toList());
    }

    // "A-01:30.00" is a portion of 30 kg in the first grade A lot of this year.
    private void assertPortions(String... expected) {
        String prefix = "-" + KigaliTime.today().getYear() + "-";
        assertEquals(List.of(expected), portions.stream()
                .map(portion -> portion.getLot().getLotCode().replace(prefix, "-") + ":"
                        + portion.getQuantityKg().setScale(2).toPlainString())
                .toList());
    }

    private Delivery confirmed(Grade grade, String kg) {
        Delivery delivery = delivery(grade, kg);
        delivery.confirm(ConfirmationMethod.OTP, Instant.now());
        return delivery;
    }

    private Delivery delivery(Grade grade, String kg) {
        Member member = new Member();
        ReflectionTestUtils.setField(member, "id", nextDeliveryId + 100);
        Delivery delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", nextDeliveryId++);
        delivery.setMember(member);
        delivery.setGrade(grade);
        delivery.setQuantityKg(new BigDecimal(kg));
        delivery.setDeliveredAt(Instant.now());
        return delivery;
    }

    private static Grade grade(Long id, String code, String name) {
        Grade grade = new Grade();
        ReflectionTestUtils.setField(grade, "id", id);
        grade.setCode(code);
        grade.setName(name);
        return grade;
    }
}
