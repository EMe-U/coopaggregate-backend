package com.coopaggregate.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.ledger.LedgerEntry;
import com.coopaggregate.ledger.LedgerEntryRepository;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotQuantities;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;
import com.coopaggregate.member.MemberStatus;
import com.coopaggregate.sale.SaleRepository;
import com.coopaggregate.setting.Setting;
import com.coopaggregate.setting.SettingRepository;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

class DeliveryServiceTest {

    private static final UUID CLIENT_UUID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    private DeliveryRepository deliveryRepository;
    private MemberRepository memberRepository;
    private LotRepository lotRepository;
    private LedgerService ledgerService;
    private SaleRepository saleRepository;
    private LedgerEntryRepository ledgerEntryRepository;
    private Setting setting;
    private Grade grade;
    private Member member;
    private Manager manager;
    private DeliveryService service;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(DeliveryRepository.class);
        memberRepository = mock(MemberRepository.class);
        lotRepository = mock(LotRepository.class);
        ledgerService = mock(LedgerService.class);
        GradeRepository gradeRepository = mock(GradeRepository.class);
        SettingRepository settingRepository = mock(SettingRepository.class);

        grade = new Grade();
        ReflectionTestUtils.setField(grade, "id", 1L);
        grade.setCode("A");
        grade.setName("Big");
        when(gradeRepository.findForUpdateById(1L)).thenReturn(Optional.of(grade));

        member = new Member();
        ReflectionTestUtils.setField(member, "id", 5L);
        member.setMemberCode("M-0005");
        member.setFullName("Uwimana Claudine");
        when(memberRepository.findById(5L)).thenReturn(Optional.of(member));

        setting = new Setting();
        when(settingRepository.findById(1L)).thenReturn(Optional.of(setting));

        when(deliveryRepository.findByClientUuid(any())).thenReturn(Optional.empty());
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(invocation -> {
            Delivery delivery = invocation.getArgument(0);
            ReflectionTestUtils.setField(delivery, "id", 100L);
            return delivery;
        });
        when(lotRepository.save(any(Lot.class))).thenAnswer(invocation -> invocation.getArgument(0));

        manager = new Manager();
        saleRepository = mock(SaleRepository.class);
        ledgerEntryRepository = mock(LedgerEntryRepository.class);
        service = new DeliveryService(deliveryRepository, memberRepository, gradeRepository, settingRepository,
                new LotService(lotRepository, gradeRepository), ledgerService, lotRepository, saleRepository,
                ledgerEntryRepository);
    }

    @ParameterizedTest
    @CsvSource({"100, 500", "0.01, 0", "12.25, 61", "12.30, 62", "5000, 25000"})
    void deductionIsKilogramsTimesFiveRoundedToWholeRwf(String quantityKg, long expectedDeduction) {
        givenOpenLot();

        DeliveryResponse response = record(quantityKg).delivery();

        assertEquals(expectedDeduction, response.deductionRwf());
    }

    @Test
    void deductionUsesRateFromSettings() {
        givenOpenLot();
        setting.setDeductionPerKg(7L);

        assertEquals(70L, record("10").delivery().deductionRwf());
    }

    @Test
    void deliveryGoesToOpenLotOfItsGrade() {
        Lot openLot = givenOpenLot();

        DeliveryResponse response = record("100").delivery();

        assertEquals(openLot.getId(), response.lotId());
        assertEquals("A-2026-01", response.lotCode());
        verify(lotRepository, never()).save(any());
    }

    @Test
    void newLotIsOpenedWhenGradeHasNoOpenLot() {
        int year = KigaliTime.today().getYear();
        when(lotRepository.findFirstByGradeAndStatus(grade, LotStatus.OPEN)).thenReturn(Optional.empty());
        when(lotRepository.countByLotCodeStartingWith("A-" + year + "-")).thenReturn(2L);

        DeliveryResponse response = record("100").delivery();

        ArgumentCaptor<Lot> lot = ArgumentCaptor.forClass(Lot.class);
        verify(lotRepository).save(lot.capture());
        assertEquals("A-" + year + "-03", lot.getValue().getLotCode());
        assertEquals(LotStatus.OPEN, lot.getValue().getStatus());
        assertEquals(KigaliTime.today(), lot.getValue().getOpenDate());
        assertEquals(grade, lot.getValue().getGrade());
        assertEquals("A-" + year + "-03", response.lotCode());
    }

    @Test
    void inactiveMemberIsRejected() {
        givenOpenLot();
        member.setStatus(MemberStatus.INACTIVE);

        BadRequestException e = assertThrows(BadRequestException.class, () -> record("100"));

        assertEquals("Member M-0005 is inactive. Activate the member before recording a delivery.", e.getMessage());
        verify(deliveryRepository, never()).save(any());
        verify(ledgerService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unknownMemberIsRejected() {
        when(memberRepository.findById(99L)).thenReturn(Optional.empty());

        BadRequestException e = assertThrows(BadRequestException.class, () -> service.record(
                new DeliveryRequest(CLIENT_UUID, 99L, 1L, new BigDecimal("100"), null), manager));

        assertEquals("Member 99 does not exist.", e.getMessage());
    }

    @Test
    void unknownGradeIsRejected() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.record(
                new DeliveryRequest(CLIENT_UUID, 5L, 9L, new BigDecimal("100"), null), manager));

        assertEquals("Grade 9 does not exist.", e.getMessage());
    }

    @Test
    void sameClientUuidReturnsExistingDelivery() {
        Lot lot = givenOpenLot();
        Delivery existing = new Delivery();
        ReflectionTestUtils.setField(existing, "id", 42L);
        existing.setClientUuid(CLIENT_UUID);
        existing.setMember(member);
        existing.setLot(lot);
        existing.setQuantityKg(new BigDecimal("80.00"));
        existing.setDeductionAmount(400L);
        existing.setReceiptCode("RCT-7K2Q");
        when(deliveryRepository.findByClientUuid(CLIENT_UUID)).thenReturn(Optional.of(existing));
        member.setStatus(MemberStatus.INACTIVE);

        RecordedDelivery result = record("100");

        assertFalse(result.created());
        assertEquals(42L, result.delivery().id());
        assertEquals("RCT-7K2Q", result.delivery().receiptCode());
        verify(deliveryRepository, never()).save(any());
        verify(ledgerService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void deliveryIsRecordedInLedger() {
        givenOpenLot();

        RecordedDelivery result = record("120.50");

        assertTrue(result.created());
        verify(ledgerService).record(LedgerEntryType.DELIVERY, "DELIVERY", 100L, new BigDecimal("120.50"), 603L,
                "Delivery " + result.delivery().receiptCode(), manager);
    }

    @Test
    void receiptCodeIsShortAndReadable() {
        givenOpenLot();

        String receiptCode = record("100").delivery().receiptCode();

        assertTrue(receiptCode.matches("RCT-[2-9A-HJKMNP-Z]{4}"), receiptCode);
    }

    @Test
    void receiptCodeIsGeneratedAgainWhenTaken() {
        givenOpenLot();
        when(deliveryRepository.existsByReceiptCode(anyString())).thenReturn(true, true, false);

        record("100");

        verify(deliveryRepository, times(3)).existsByReceiptCode(anyString());
    }

    @Test
    void deliveredAtDefaultsToNow() {
        givenOpenLot();
        Instant before = Instant.now();

        Instant deliveredAt = record("100").delivery().deliveredAt();

        assertFalse(deliveredAt.isBefore(before));
        assertFalse(deliveredAt.isAfter(Instant.now()));
    }

    @Test
    void deliveredAtFromDeviceIsKept() {
        givenOpenLot();
        Instant offlineTime = Instant.now().minus(Duration.ofHours(5)).truncatedTo(ChronoUnit.SECONDS);

        RecordedDelivery result = service.record(
                new DeliveryRequest(CLIENT_UUID, 5L, 1L, new BigDecimal("100"), offlineTime), manager);

        assertEquals(offlineTime, result.delivery().deliveredAt());
    }

    @Test
    void deliveryRecordedOfflineDaysAgoIsAccepted() {
        givenOpenLot();
        Instant sixDaysAgo = Instant.now().minus(Duration.ofDays(6));

        assertEquals(sixDaysAgo, recordAt(sixDaysAgo).delivery().deliveredAt());
    }

    @Test
    void deviceClockSlightlyAheadIsAccepted() {
        givenOpenLot();
        Instant fourMinutesAhead = Instant.now().plus(Duration.ofMinutes(4));

        assertEquals(fourMinutesAhead, recordAt(fourMinutesAhead).delivery().deliveredAt());
    }

    @Test
    void deliveredAtInTheFutureIsRejected() {
        givenOpenLot();

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> recordAt(Instant.now().plus(Duration.ofMinutes(10))));

        assertEquals("deliveredAt is in the future. Check the date and time on the device.", e.getMessage());
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void deliveredAtMoreThanSevenDaysAgoIsRejected() {
        givenOpenLot();

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> recordAt(Instant.now().minus(Duration.ofDays(7)).minus(Duration.ofMinutes(1))));

        assertEquals("deliveredAt is more than 7 days ago. Offline deliveries must be synced within 7 days.",
                e.getMessage());
        verify(deliveryRepository, never()).save(any());
    }

    @Test
    void alreadySavedDeliveryIsReturnedEvenWhenItsTimeIsOld() {
        Lot lot = givenOpenLot();
        Delivery existing = existingDelivery(lot);
        existing.setDeliveredAt(Instant.now().minus(Duration.ofDays(10)));
        when(deliveryRepository.findByClientUuid(CLIENT_UUID)).thenReturn(Optional.of(existing));

        RecordedDelivery result = recordAt(existing.getDeliveredAt());

        assertFalse(result.created());
        assertEquals(42L, result.delivery().id());
    }

    @Test
    void sameOfflineDeliverySentThreeTimesIsSavedOnce() {
        givenOpenLot();
        // Behaves like the database: once saved, the delivery is found by its client UUID.
        AtomicReference<Delivery> stored = new AtomicReference<>();
        when(deliveryRepository.findByClientUuid(CLIENT_UUID))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(invocation -> {
            Delivery delivery = invocation.getArgument(0);
            ReflectionTestUtils.setField(delivery, "id", 100L);
            stored.set(delivery);
            return delivery;
        });
        Instant recordedOffline = Instant.now().minus(Duration.ofHours(3));

        RecordedDelivery first = recordAt(recordedOffline);
        RecordedDelivery second = recordAt(recordedOffline);
        RecordedDelivery third = recordAt(recordedOffline);

        assertTrue(first.created());
        assertFalse(second.created());
        assertFalse(third.created());
        assertEquals(first.delivery(), second.delivery());
        assertEquals(first.delivery(), third.delivery());
        verify(deliveryRepository, times(1)).save(any(Delivery.class));
        verify(ledgerService, times(1)).record(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void responseShowsWhenTheServerSavedTheDelivery() {
        givenOpenLot();
        Instant savedAt = Instant.now();
        when(deliveryRepository.save(any(Delivery.class))).thenAnswer(invocation -> {
            Delivery delivery = invocation.getArgument(0);
            ReflectionTestUtils.setField(delivery, "id", 100L);
            // Hibernate fills created_at when the row is inserted.
            ReflectionTestUtils.setField(delivery, "createdAt", savedAt);
            return delivery;
        });
        Instant recordedOffline = savedAt.minus(Duration.ofHours(4));

        DeliveryResponse response = recordAt(recordedOffline).delivery();

        assertEquals(recordedOffline, response.deliveredAt());
        assertEquals(savedAt, response.syncedAt());
    }

    @Test
    void offlineDeliveryGoesToTheLotThatIsOpenWhenItSyncs() {
        Lot openNow = givenOpenLot();

        DeliveryResponse response = recordAt(Instant.now().minus(Duration.ofDays(2))).delivery();

        assertEquals(openNow.getId(), response.lotId());
    }

    @Test
    void offlineDeliveryOpensANewLotWhenItsLotWasClosedBeforeSync() {
        int year = KigaliTime.today().getYear();
        when(lotRepository.findFirstByGradeAndStatus(grade, LotStatus.OPEN)).thenReturn(Optional.empty());
        when(lotRepository.countByLotCodeStartingWith("A-" + year + "-")).thenReturn(1L);

        DeliveryResponse response = recordAt(Instant.now().minus(Duration.ofDays(2))).delivery();

        ArgumentCaptor<Lot> lot = ArgumentCaptor.forClass(Lot.class);
        verify(lotRepository).save(lot.capture());
        assertEquals("A-" + year + "-02", response.lotCode());
        // The new lot opens on the sync day, not on the day the delivery was recorded offline.
        assertEquals(KigaliTime.today(), lot.getValue().getOpenDate());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deliveryListLeavesOutReversedDeliveriesUnlessAsked() {
        Root<Delivery> root = mock(Root.class, RETURNS_DEEP_STUBS);
        Path<Object> reversedAt = root.get("reversedAt");

        CriteriaBuilder defaultList = mock(CriteriaBuilder.class);
        DeliveryService.matching(null, null, null, null, false)
                .toPredicate(root, mock(CriteriaQuery.class), defaultList);
        verify(defaultList).isNull(reversedAt);

        CriteriaBuilder withReversed = mock(CriteriaBuilder.class);
        DeliveryService.matching(null, null, null, null, true)
                .toPredicate(root, mock(CriteriaQuery.class), withReversed);
        verify(withReversed, never()).isNull(reversedAt);
    }

    // --- Reversing a delivery ---

    @Test
    void reversingMarksTheDeliveryAndAddsALedgerReversal() {
        Delivery delivery = givenStoredDelivery("100.00");
        LedgerEntry reversal = reversalEntry("Weighed twice");
        when(ledgerService.reverse(77L, "Weighed twice", manager)).thenReturn(reversal);

        Delivery reversed = service.reverse(42L, "Weighed twice", manager);

        assertTrue(reversed.isReversed());
        assertEquals("Weighed twice", reversed.getReversalReason());
        assertEquals(reversal.getEntryDate(), reversed.getReversedAt());
        assertEquals(reversal, reversed.getReversalLedgerEntry());
        assertTrue(DeliveryResponse.from(reversed).reversed());
    }

    @Test
    void deliveryOfALotWithSalesCannotBeReversed() {
        givenStoredDelivery("100.00");
        when(saleRepository.existsByLotId(3L)).thenReturn(true);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reverse(42L, "Weighed twice", manager));

        assertEquals("Lot A-2026-01 already has sales, so its deliveries can no longer be reversed or corrected.",
                e.getMessage());
        verify(ledgerService, never()).reverse(any(), any(), any());
    }

    @Test
    void deliveryCannotBeReversedTwice() {
        Delivery delivery = givenStoredDelivery("100.00");
        delivery.markReversed(reversalEntry("First time"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reverse(42L, "Again", manager));

        assertEquals("Delivery RCT-7K2Q has already been reversed.", e.getMessage());
        verify(ledgerService, never()).reverse(any(), any(), any());
    }

    @Test
    void reversalThatWouldLeaveLessThanZeroKgIsRefused() {
        givenStoredDelivery("100.00");
        // The lot holds 150 kg, of which 80 kg were lost: only 70 kg remain, less than the 100 kg delivery.
        when(lotRepository.findQuantities(any())).thenReturn(List.of(new LotQuantities(3L,
                new BigDecimal("150.00"), new BigDecimal("80.00"), null, null, null)));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reverse(42L, "Weighed twice", manager));

        assertEquals("Lot A-2026-01 would have less than 0 kg left, because some of its potatoes were already "
                + "recorded as lost.", e.getMessage());
    }

    @Test
    void reversingAnUnknownDeliveryIsNotFound() {
        when(deliveryRepository.findLotIdById(99L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.reverse(99L, "Unknown", manager));
    }

    private Delivery givenStoredDelivery(String quantityKg) {
        Lot lot = givenOpenLot();
        Delivery delivery = existingDelivery(lot);
        delivery.setQuantityKg(new BigDecimal(quantityKg));
        when(deliveryRepository.findLotIdById(42L)).thenReturn(Optional.of(3L));
        when(lotRepository.findForUpdateById(3L)).thenReturn(Optional.of(lot));
        when(deliveryRepository.findById(42L)).thenReturn(Optional.of(delivery));
        when(lotRepository.findQuantities(any())).thenReturn(List.of(new LotQuantities(3L,
                new BigDecimal("500.00"), null, null, null, null)));
        LedgerEntry entry = new LedgerEntry();
        ReflectionTestUtils.setField(entry, "id", 77L);
        when(ledgerEntryRepository.findFirstByEntryTypeAndRelatedEntityTypeAndRelatedEntityId(
                LedgerEntryType.DELIVERY, "DELIVERY", 42L)).thenReturn(Optional.of(entry));
        return delivery;
    }

    private static LedgerEntry reversalEntry(String reason) {
        LedgerEntry reversal = new LedgerEntry();
        ReflectionTestUtils.setField(reversal, "id", 90L);
        reversal.setEntryType(LedgerEntryType.REVERSAL);
        reversal.setEntryDate(Instant.parse("2026-10-15T09:00:00Z"));
        reversal.setReason(reason);
        return reversal;
    }

    private RecordedDelivery recordAt(Instant deliveredAt) {
        return service.record(new DeliveryRequest(CLIENT_UUID, 5L, 1L, new BigDecimal("100"), deliveredAt), manager);
    }

    private Delivery existingDelivery(Lot lot) {
        Delivery existing = new Delivery();
        ReflectionTestUtils.setField(existing, "id", 42L);
        existing.setClientUuid(CLIENT_UUID);
        existing.setMember(member);
        existing.setLot(lot);
        existing.setQuantityKg(new BigDecimal("80.00"));
        existing.setDeductionAmount(400L);
        existing.setReceiptCode("RCT-7K2Q");
        return existing;
    }

    private RecordedDelivery record(String quantityKg) {
        return service.record(new DeliveryRequest(CLIENT_UUID, 5L, 1L, new BigDecimal(quantityKg), null), manager);
    }

    private Lot givenOpenLot() {
        Lot lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", 3L);
        lot.setLotCode("A-2026-01");
        lot.setGrade(grade);
        when(lotRepository.findFirstByGradeAndStatus(grade, LotStatus.OPEN)).thenReturn(Optional.of(lot));
        return lot;
    }
}
