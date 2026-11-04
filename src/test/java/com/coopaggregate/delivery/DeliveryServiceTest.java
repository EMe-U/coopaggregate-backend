package com.coopaggregate.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.aopalliance.intercept.MethodInterceptor;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.auth.AuthService;
import com.coopaggregate.batch.BatchAllocator;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.ledger.LedgerEntry;
import com.coopaggregate.ledger.LedgerEntryRepository;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.ledger.ReverseRequest;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotQuantities;
import com.coopaggregate.lot.LotPortion;
import com.coopaggregate.lot.LotPortionRepository;
import com.coopaggregate.lot.LotFiller;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.lot.PortionResponse;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;
import com.coopaggregate.member.MemberStatus;
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
    private GradeRepository gradeRepository;
    private ApplicationEventPublisher eventPublisher;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private ConfirmationCodes confirmationCodes;
    private AuditLogRepository auditLogRepository;
    private BatchAllocator batchAllocator;
    private LedgerEntryRepository ledgerEntryRepository;
    private Setting setting;
    private Grade grade;
    private Member member;
    private Manager manager;
    private LotFiller lotFiller;
    private LotPortionRepository lotPortionRepository;
    private DeliveryService service;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(DeliveryRepository.class);
        memberRepository = mock(MemberRepository.class);
        lotRepository = mock(LotRepository.class);
        ledgerService = mock(LedgerService.class);
        gradeRepository = mock(GradeRepository.class);
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
        ledgerEntryRepository = mock(LedgerEntryRepository.class);
        when(memberRepository.findForUpdateById(5L)).thenReturn(Optional.of(member));
        eventPublisher = mock(ApplicationEventPublisher.class);
        confirmationCodes = new ConfirmationCodes(encoder);
        auditLogRepository = mock(AuditLogRepository.class);
        batchAllocator = mock(BatchAllocator.class);
        lotFiller = mock(LotFiller.class);
        lotPortionRepository = mock(LotPortionRepository.class);
        service = new DeliveryService(deliveryRepository, memberRepository, gradeRepository, settingRepository,
                ledgerService, lotRepository, ledgerEntryRepository, confirmationCodes, auditLogRepository,
                batchAllocator, lotFiller, lotPortionRepository, eventPublisher);
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
    void aNewDeliveryIsInNoLotUntilItIsConfirmed() {
        DeliveryResponse response = record("100").delivery();

        assertNull(response.lotId());
        assertNull(response.lotCode());
        assertEquals("Big", response.gradeName());
        verify(lotRepository, never()).save(any());
        verify(lotFiller, never()).place(any());
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
        existing.setGrade(lot.getGrade());
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
    void anOfflineDeliveryIsAlsoInNoLotUntilItIsConfirmed() {
        DeliveryResponse response = recordAt(Instant.now().minus(Duration.ofDays(2))).delivery();

        assertNull(response.lotId());
        verify(lotFiller, never()).place(any());
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
    void reversedDeliveryHandsItsLostKgBackToTheLot() {
        Delivery delivery = givenStoredDelivery("100.00");
        when(ledgerService.reverse(77L, "Weighed twice", manager)).thenReturn(reversalEntry("Weighed twice"));

        service.reverse(42L, "Weighed twice", manager);

        verify(batchAllocator).releaseLosses(delivery);
    }

    @Test
    void refusedReversalChangesNoBatch() {
        Delivery delivery = givenStoredDelivery("100.00");
        delivery.addSoldKg(new BigDecimal("40.00"));

        assertThrows(IllegalStateException.class, () -> service.reverse(42L, "Weighed twice", manager));

        verify(batchAllocator, never()).releaseLosses(any());
    }

    @Test
    void correctionHandsTheLostKgBackOnlyAfterTheCorrectedDeliveryIsSaved() {
        Delivery original = givenStoredDelivery("100.00");
        when(ledgerService.reverse(any(), any(), any())).thenReturn(reversalEntry("Weight"));

        service.correct(42L, new CorrectDeliveryRequest("Weight", 5L, 1L, new BigDecimal("90.00")), manager);

        InOrder order = inOrder(deliveryRepository, batchAllocator);
        order.verify(deliveryRepository).save(any(Delivery.class));
        order.verify(batchAllocator).releaseLosses(original);
    }

    @Test
    void deliveryWithSoldKgCannotBeReversed() {
        Delivery delivery = givenStoredDelivery("100.00");
        delivery.addSoldKg(new BigDecimal("40.00"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reverse(42L, "Weighed twice", manager));

        assertEquals("Delivery RCT-7K2Q already has 40.00 kg sold, so it can no longer be reversed or corrected.",
                e.getMessage());
        verify(ledgerService, never()).reverse(any(), any(), any());
    }

    @Test
    void deliveryOfALotWithSalesCanBeReversedWhileItsOwnKgAreNotSold() {
        givenStoredDelivery("100.00");
        // The lot sold 60 kg from other batches, and 440 kg remain: this delivery's 100 kg are still unsold.
        when(lotRepository.findQuantities(any())).thenReturn(List.of(new LotQuantities(3L,
                new BigDecimal("500.00"), null, new BigDecimal("60.00"), 18_000L, 2L)));
        when(ledgerService.reverse(77L, "Weighed twice", manager)).thenReturn(reversalEntry("Weighed twice"));

        assertTrue(service.reverse(42L, "Weighed twice", manager).isReversed());
    }

    @Test
    void deliveryOfASharedLotCannotBeReversed() {
        Delivery delivery = givenStoredDelivery("100.00");
        delivery.getLot().setStatus(LotStatus.SHARED);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reverse(42L, "Weighed twice", manager));

        assertEquals("Lot A-2026-01 has already been shared, so its deliveries can no longer be reversed or "
                + "corrected.", e.getMessage());
        verify(ledgerService, never()).reverse(any(), any(), any());
    }

    @Test
    void reversalLocksTheGradeBeforeTheLot() {
        givenStoredDelivery("100.00");
        when(ledgerService.reverse(77L, "Weighed twice", manager)).thenReturn(reversalEntry("Weighed twice"));

        service.reverse(42L, "Weighed twice", manager);

        InOrder order = inOrder(gradeRepository, lotRepository);
        order.verify(gradeRepository).findForUpdateById(1L);
        order.verify(lotRepository).findForUpdateById(3L);
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
        Delivery delivery = givenStoredDelivery("100.00");
        // 30 kg of the delivery were lost, and the lot's other portions have only 20 kg left to take them.
        LotPortion portion = givenPortion(delivery, delivery.getLot(), "100.00", 5L);
        portion.addLostKg(new BigDecimal("30.00"));
        when(batchAllocator.lossCarryingKg(delivery.getLot(), delivery)).thenReturn(new BigDecimal("20.00"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reverse(42L, "Weighed twice", manager));

        assertEquals("Lot A-2026-01 would have less than 0 kg left, because some of its potatoes were already "
                + "recorded as lost.", e.getMessage());
        assertFalse(delivery.isReversed());
        verify(ledgerService, never()).reverse(any(), any(), any());
    }

    @Test
    void aWaitingDeliveryInNoLotCanBeReversed() {
        Delivery delivery = givenStoredDelivery("100.00");
        delivery.setLot(null);
        when(deliveryRepository.findLotIdById(42L)).thenReturn(Optional.empty());
        when(ledgerService.reverse(77L, "Weighed twice", manager)).thenReturn(reversalEntry("Weighed twice"));

        service.reverse(42L, "Weighed twice", manager);

        assertTrue(delivery.isReversed());
        verify(gradeRepository).findForUpdateById(1L);
    }

    // --- A delivery in fixed-size lots ---

    @Test
    void aDeliverySplitAcrossTwoLotsIsReversedWithTheLocksInOrderAndItsLossesHandedOn() {
        Delivery delivery = givenStoredDelivery("100.00");
        delivery.setLot(null);
        when(deliveryRepository.findLotIdById(42L)).thenReturn(Optional.empty());
        Lot full = fixedLot(7L, "A-2026-05", LotStatus.FULL);
        Lot filling = fixedLot(6L, "A-2026-04", LotStatus.FILLING);
        LotPortion inFull = givenPortion(delivery, full, "40.00", 70L);
        givenPortion(delivery, filling, "60.00", 71L);
        inFull.addLostKg(new BigDecimal("4.00"));
        when(batchAllocator.lossCarryingKg(full, delivery)).thenReturn(new BigDecimal("50.00"));
        when(lotPortionRepository.remainingKg(7L)).thenReturn(new BigDecimal("46.00"));
        when(ledgerService.reverse(77L, "Wrong member", manager)).thenReturn(reversalEntry("Wrong member"));

        service.reverse(42L, "Wrong member", manager);

        assertTrue(delivery.isReversed());
        InOrder order = inOrder(gradeRepository, lotRepository, deliveryRepository, batchAllocator, ledgerService);
        order.verify(gradeRepository).findForUpdateById(1L);
        order.verify(lotRepository).findForUpdateById(6L);
        order.verify(lotRepository).findForUpdateById(7L);
        order.verify(deliveryRepository).findForUpdateById(42L);
        order.verify(ledgerService).reverse(77L, "Wrong member", manager);
        order.verify(batchAllocator).releaseLosses(delivery);
        // The full lot keeps its shortfall and is never filled again; the lot being filled gets the space back.
        assertEquals(LotStatus.FULL, full.getStatus());
        assertEquals(LotStatus.FILLING, filling.getStatus());
    }

    @Test
    void aFullLotThatTheReversalLeavesWithNothingToSellIsSoldOut() {
        Delivery delivery = givenStoredDelivery("40.00");
        delivery.setLot(null);
        when(deliveryRepository.findLotIdById(42L)).thenReturn(Optional.empty());
        Lot full = fixedLot(7L, "A-2026-05", LotStatus.FULL);
        givenPortion(delivery, full, "40.00", 70L);
        when(lotPortionRepository.remainingKg(7L)).thenReturn(BigDecimal.ZERO);
        when(ledgerService.reverse(any(), any(), any())).thenReturn(reversalEntry("Wrong"));

        service.reverse(42L, "Wrong", manager);

        assertEquals(LotStatus.SOLD, full.getStatus());
    }

    @Test
    void aDeliveryInLotsWithSoldKgCannotBeReversedOrCorrected() {
        Delivery delivery = givenStoredDelivery("100.00");
        delivery.setLot(null);
        givenPortion(delivery, fixedLot(7L, "A-2026-05", LotStatus.FULL), "100.00", 70L).addSoldKg(BigDecimal.ONE);
        delivery.addSoldKg(BigDecimal.ONE);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reverse(42L, "Wrong", manager));
        assertThrows(IllegalStateException.class, () -> service.correct(42L,
                new CorrectDeliveryRequest("Weight", 5L, 1L, new BigDecimal("90.00")), manager));

        assertEquals("Delivery RCT-7K2Q already has 1 kg sold, so it can no longer be reversed or corrected.",
                e.getMessage());
        verify(ledgerService, never()).reverse(any(), any(), any());
        verify(batchAllocator, never()).releaseLosses(any());
    }

    @Test
    void aCorrectionToAnotherGradeLocksBothGradesInIdOrderThenTheLots() {
        Delivery delivery = givenStoredDelivery("100.00");
        Grade normal = new Grade();
        ReflectionTestUtils.setField(normal, "id", 2L);
        normal.setCode("B");
        normal.setName("Normal");
        when(gradeRepository.findForUpdateById(2L)).thenReturn(Optional.of(normal));
        when(ledgerService.reverse(any(), any(), any())).thenReturn(reversalEntry("Wrong grade"));

        service.correct(42L, new CorrectDeliveryRequest("Wrong grade", 5L, 2L, new BigDecimal("100.00")), manager);

        InOrder order = inOrder(gradeRepository, lotRepository, deliveryRepository);
        order.verify(gradeRepository).findForUpdateById(1L);
        order.verify(gradeRepository).findForUpdateById(2L);
        order.verify(lotRepository).findForUpdateById(3L);
        order.verify(deliveryRepository).findForUpdateById(42L);
        assertEquals(normal, lastSaved().getGrade());
        assertTrue(delivery.isReversed());
    }

    @Test
    void disputingADeliveryLocksTheGradeFirstSoNoSaleSellsItMeanwhile() {
        Delivery delivery = givenStoredDelivery("100.00");
        delivery.confirm(ConfirmationMethod.OTP, Instant.now());

        service.dispute(42L, DisputeReason.WRONG_KG, null);

        InOrder order = inOrder(gradeRepository, deliveryRepository);
        order.verify(gradeRepository).findForUpdateById(1L);
        order.verify(deliveryRepository).findForUpdateById(42L);
        assertEquals(ConfirmationStatus.DISPUTED, delivery.getConfirmationStatus());
        // Its kg stay in their lot; a resolved dispute does not place it again.
        verify(lotFiller, never()).place(any());
    }

    @Test
    void reversingAnUnknownDeliveryIsNotFound() {
        when(deliveryRepository.findLotIdById(99L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.reverse(99L, "Unknown", manager));
    }

    // --- Correcting a delivery ---

    @Test
    void correctionMovesTheKilogramsToTheRightMemberAndGrade() {
        Delivery original = givenStoredDelivery("100.00");
        Member bosco = new Member();
        ReflectionTestUtils.setField(bosco, "id", 6L);
        bosco.setMemberCode("M-0006");
        bosco.setFullName("Bosco Habimana");
        when(memberRepository.findById(6L)).thenReturn(Optional.of(bosco));
        Grade normal = new Grade();
        ReflectionTestUtils.setField(normal, "id", 2L);
        normal.setCode("B");
        normal.setName("Normal");
        when(gradeRepository.findForUpdateById(2L)).thenReturn(Optional.of(normal));
        when(ledgerService.reverse(77L, "Wrong member and grade", manager)).thenReturn(reversalEntry("Wrong"));

        service.correct(42L,
                new CorrectDeliveryRequest("Wrong member and grade", 6L, 2L, new BigDecimal("95.50")), manager);
        Delivery corrected = lastSaved();

        assertTrue(original.isReversed());
        assertEquals(bosco, corrected.getMember());
        assertEquals(normal, corrected.getGrade());
        // Like any new delivery, the correction waits for the member and only then goes into a lot.
        assertNull(corrected.getLot());
        assertEquals(ConfirmationStatus.PENDING, corrected.getConfirmationStatus());
        assertEquals(new BigDecimal("95.50"), corrected.getQuantityKg());
        assertEquals(478L, corrected.getDeductionAmount());
        assertEquals(original, corrected.getCorrects());
        assertEquals(original.getDeliveredAt(), corrected.getDeliveredAt());
        assertTrue(!corrected.getReceiptCode().equals(original.getReceiptCode()));
        verify(ledgerService).record(LedgerEntryType.DELIVERY, "DELIVERY", 100L, new BigDecimal("95.50"), 478L,
                "Delivery " + corrected.getReceiptCode() + " corrects RCT-7K2Q", manager);
        assertEquals(42L, DeliveryResponse.from(corrected).correctsId());
    }

    @Test
    void aCorrectionNoLongerPutsItsKilogramsBackIntoTheOldLot() {
        Delivery delivery = givenStoredDelivery("100.00");
        // 30 kg of the delivery were lost and the lot's others have only 20 kg left to take them. The corrected 90 kg
        // wait for the member's confirmation and then go into a fixed-size lot, not back into this one.
        givenPortion(delivery, delivery.getLot(), "100.00", 5L).addLostKg(new BigDecimal("30.00"));
        when(batchAllocator.lossCarryingKg(delivery.getLot(), delivery)).thenReturn(new BigDecimal("20.00"));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.correct(42L,
                new CorrectDeliveryRequest("Weight", 5L, 1L, new BigDecimal("90.00")), manager));

        assertEquals("Lot A-2026-01 would have less than 0 kg left, because some of its potatoes were already "
                + "recorded as lost.", e.getMessage());
    }

    @Test
    void correctionIsBlockedOnceTheBatchHasSoldKg() {
        Delivery delivery = givenStoredDelivery("100.00");
        delivery.addSoldKg(new BigDecimal("40.00"));

        assertThrows(IllegalStateException.class, () -> service.correct(42L,
                new CorrectDeliveryRequest("Weight", 5L, 1L, new BigDecimal("90.00")), manager));

        verify(deliveryRepository, never()).save(any());
        verify(ledgerService, never()).reverse(any(), any(), any());
    }

    @Test
    void correctionToAnInactiveMemberIsRejected() {
        givenStoredDelivery("100.00");
        member.setStatus(MemberStatus.INACTIVE);

        assertThrows(BadRequestException.class, () -> service.correct(42L,
                new CorrectDeliveryRequest("Weight", 5L, 1L, new BigDecimal("90.00")), manager));
        verify(ledgerService, never()).reverse(any(), any(), any());
    }

    @Test
    void originalShowsWhichDeliveryCorrectedIt() {
        Delivery original = givenStoredDelivery("100.00");
        Delivery corrected = new Delivery();
        ReflectionTestUtils.setField(corrected, "id", 43L);
        when(deliveryRepository.findByCorrectsIdIn(List.of(42L))).thenReturn(List.of(corrected));

        assertEquals(43L, service.toResponse(original).correctedById());
    }

    private Delivery givenStoredDelivery(String quantityKg) {
        Lot lot = givenOpenLot();
        Delivery delivery = existingDelivery(lot);
        delivery.setQuantityKg(new BigDecimal(quantityKg));
        delivery.setDeliveredAt(Instant.parse("2026-10-14T07:30:00Z"));
        when(deliveryRepository.findGradeIdById(42L)).thenReturn(Optional.of(1L));
        when(deliveryRepository.findLotIdById(42L)).thenReturn(Optional.of(3L));
        when(lotRepository.findForUpdateById(3L)).thenReturn(Optional.of(lot));
        when(deliveryRepository.findById(42L)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.findForUpdateById(42L)).thenReturn(Optional.of(delivery));
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

    // --- Confirming with the one-time code ---

    @Test
    void newDeliveryWaitsForTheMembersCode() {
        givenOpenLot();

        DeliveryResponse response = record("100").delivery();

        assertEquals(ConfirmationStatus.PENDING, response.confirmationStatus());
        assertEquals(null, response.confirmationMethod());
    }

    @Test
    void aDeliverySplitAcrossTwoLotsListsBothFirstLotFirst() {
        Delivery delivery = givenStoredDelivery("50.00");
        delivery.setLot(null);
        Lot third = fixedLot(7L, "A-2026-03", LotStatus.FULL);
        Lot fourth = fixedLot(8L, "A-2026-04", LotStatus.FILLING);
        LotPortion inFourth = new LotPortion(fourth, delivery, new BigDecimal("20.00"));
        inFourth.addSoldKg(new BigDecimal("5.00"));
        when(lotPortionRepository.findWithLotByDeliveryIdIn(List.of(42L))).thenReturn(List.of(inFourth,
                new LotPortion(third, delivery, new BigDecimal("30.00"))));

        DeliveryResponse response = service.get(42L);

        assertEquals(7L, response.lotId());
        assertEquals("A-2026-03", response.lotCode());
        assertEquals(List.of(
                new PortionResponse(7L, "A-2026-03", LotStatus.FULL, new BigDecimal("30.00"), BigDecimal.ZERO,
                        BigDecimal.ZERO, new BigDecimal("30.00")),
                new PortionResponse(8L, "A-2026-04", LotStatus.FILLING, new BigDecimal("20.00"),
                        new BigDecimal("5.00"), BigDecimal.ZERO, new BigDecimal("15.00"))), response.lots());
    }

    @Test
    void aWaitingDeliveryIsInNoLot() {
        Delivery delivery = givenStoredDelivery("50.00");
        delivery.setLot(null);

        DeliveryResponse response = service.get(42L);

        assertNull(response.lotId());
        assertEquals(List.of(), response.lots());
    }

    @Test
    void aNewDeliveryKeepsItsOwnGrade() {
        givenOpenLot();

        record("100");

        ArgumentCaptor<Delivery> saved = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository).save(saved.capture());
        assertEquals(grade, saved.getValue().getGrade());
    }

    @Test
    void correctCodeConfirmsTheDelivery() {
        Delivery delivery = givenDeliveryWithCode();
        String code = confirmationCodes.issue(delivery, Instant.now());

        service.confirmWithCode(42L, code);

        assertEquals(ConfirmationStatus.CONFIRMED, delivery.getConfirmationStatus());
        assertEquals(ConfirmationMethod.OTP, delivery.getConfirmationMethod());
        assertEquals("Confirmed by member with the SMS code", delivery.getConfirmationMethod().label());
        assertEquals(null, delivery.getConfirmationCodeHash());
        verify(lotFiller).place(delivery);
        verify(eventPublisher).publishEvent(new DeliveryConfirmedEvent(42L));
    }

    @Test
    void wrongCodeIsCountedAndRejected() {
        Delivery delivery = givenDeliveryWithCode();
        String code = confirmationCodes.issue(delivery, Instant.now());

        WrongCodeException e = assertThrows(WrongCodeException.class,
                () -> service.confirmWithCode(42L, otherCode(code)));

        assertEquals("Wrong code. 2 tries left.", e.getMessage());
        assertEquals(1, delivery.getConfirmationCodeFailedAttempts());
        assertEquals(ConfirmationStatus.PENDING, delivery.getConfirmationStatus());
        verify(lotFiller, never()).place(any());
        verify(eventPublisher, never()).publishEvent(any(DeliveryConfirmedEvent.class));
    }

    @Test
    void threeWrongTriesKillTheCodeEvenForTheRightCode() {
        Delivery delivery = givenDeliveryWithCode();
        String code = confirmationCodes.issue(delivery, Instant.now());
        String wrong = otherCode(code);

        assertThrows(WrongCodeException.class, () -> service.confirmWithCode(42L, wrong));
        WrongCodeException second = assertThrows(WrongCodeException.class, () -> service.confirmWithCode(42L, wrong));
        assertEquals("Wrong code. 1 try left.", second.getMessage());
        WrongCodeException third = assertThrows(WrongCodeException.class, () -> service.confirmWithCode(42L, wrong));
        assertEquals("Wrong code. The code no longer works; send the member a new code.", third.getMessage());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.confirmWithCode(42L, code));
        assertEquals("Delivery RCT-7K2Q has no valid code. Send the member a new code.", e.getMessage());
        assertEquals(ConfirmationStatus.PENDING, delivery.getConfirmationStatus());
    }

    @Test
    void everyConfirmationLocksTheGradeThenItsLotBeingFilledThenTheDeliveryAndOnlyThenFillsTheLot() {
        Delivery delivery = givenDeliveryWithCode();
        String code = confirmationCodes.issue(delivery, Instant.now());

        service.confirmWithCode(42L, code);

        InOrder order = inOrder(gradeRepository, lotFiller, deliveryRepository);
        order.verify(gradeRepository).findForUpdateById(1L);
        order.verify(lotFiller).lockFillingLot(1L);
        order.verify(deliveryRepository).findForUpdateById(42L);
        order.verify(lotFiller).place(delivery);
    }

    @Test
    void theManagersReviewAndAResolvedDisputeTakeTheSameLocksInTheSameOrder() {
        Delivery delivery = givenStoredDelivery("100.00");
        setting.setSmsEnabled(false);
        service.reviewPending(42L, "Checked", manager);
        delivery.dispute(DisputeReason.WRONG_KG, null, Instant.now());
        service.resolveDispute(42L, "Checked again");

        InOrder order = inOrder(gradeRepository, lotFiller, deliveryRepository);
        for (int i = 0; i < 2; i++) {
            order.verify(gradeRepository).findForUpdateById(1L);
            order.verify(lotFiller).lockFillingLot(1L);
            order.verify(deliveryRepository).findForUpdateById(42L);
            order.verify(lotFiller).place(delivery);
        }
    }

    @Test
    void wrongTriesAreKeptWhenConfirmingFails() throws Exception {
        Transactional transactional = DeliveryService.class.getMethod("confirmWithCode", Long.class, String.class)
                .getAnnotation(Transactional.class);

        assertEquals(List.of(WrongCodeException.class), List.of(transactional.noRollbackFor()));
    }

    @Test
    void expiredCodeCannotConfirm() {
        Delivery delivery = givenDeliveryWithCode();
        String code = confirmationCodes.issue(delivery, Instant.now().minus(Duration.ofMinutes(11)));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.confirmWithCode(42L, code));

        assertEquals("The code for delivery RCT-7K2Q has expired. Send the member a new code.", e.getMessage());
        assertEquals(ConfirmationStatus.PENDING, delivery.getConfirmationStatus());
    }

    @Test
    void codeStillWorksJustBeforeItExpires() {
        Delivery delivery = givenDeliveryWithCode();
        String code = confirmationCodes.issue(delivery, Instant.now().minus(Duration.ofMinutes(9)));

        service.confirmWithCode(42L, code);

        assertEquals(ConfirmationStatus.CONFIRMED, delivery.getConfirmationStatus());
    }

    @Test
    void deliveryWithoutCodeCannotBeConfirmedWithACode() {
        givenDeliveryWithCode();

        assertThrows(IllegalStateException.class, () -> service.confirmWithCode(42L, "123456"));
    }

    @Test
    void onlyAPendingDeliveryCanBeConfirmedWithACode() {
        Delivery delivery = givenDeliveryWithCode();
        String code = confirmationCodes.issue(delivery, Instant.now());
        delivery.dispute(DisputeReason.WRONG_KG, null, Instant.now());

        assertThrows(IllegalStateException.class, () -> service.confirmWithCode(42L, code));
    }

    // --- Temporary demo code (SMS_DEMO_CODE) ---

    @Test
    void demoCodeConfirmsAnyPendingDeliveryWhenSet() {
        Delivery delivery = givenDeliveryWithCode();
        String demo = otherCode(confirmationCodes.issue(delivery, Instant.now()));
        useDemoCode(demo);

        DeliveryResponse response = service.confirmWithCode(42L, demo);

        assertEquals(ConfirmationStatus.CONFIRMED, delivery.getConfirmationStatus());
        assertEquals(ConfirmationMethod.OTP, delivery.getConfirmationMethod());
        assertEquals("Confirmed with the demo code, not the member's SMS code.", response.resolutionNote());
        // The normal receipt SMS goes out from this event.
        verify(eventPublisher).publishEvent(new DeliveryConfirmedEvent(42L));
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertEquals(AuditAction.DEMO_CODE_CONFIRMATION, audit.getValue().getAction());
        assertEquals("Delivery RCT-7K2Q confirmed with the demo code", audit.getValue().getDetails());
        assertEquals(member, audit.getValue().getMember());
        assertFalse(audit.getValue().getDetails().contains(demo));
    }

    @Test
    void demoCodePutsTheDeliveryIntoItsLotsLikeANormalConfirmation() {
        Delivery delivery = givenDeliveryWithCode();
        String demo = otherCode(confirmationCodes.issue(delivery, Instant.now()));
        useDemoCode(demo);
        AtomicReference<ConfirmationStatus> statusWhenPlaced = new AtomicReference<>();
        doAnswer(invocation -> {
            statusWhenPlaced.set(invocation.<Delivery>getArgument(0).getConfirmationStatus());
            return List.of();
        }).when(lotFiller).place(delivery);

        service.confirmWithCode(42L, demo);

        // The same filling path as the member's code: the confirmed delivery goes to the lot filler, once, before the
        // demo audit entry and the event that sends the receipt SMS.
        assertEquals(ConfirmationStatus.CONFIRMED, statusWhenPlaced.get());
        InOrder order = inOrder(lotFiller, auditLogRepository, eventPublisher);
        order.verify(lotFiller).place(delivery);
        order.verify(auditLogRepository).save(org.mockito.ArgumentMatchers.argThat(
                entry -> entry.getAction() == AuditAction.DEMO_CODE_CONFIRMATION));
        order.verify(eventPublisher).publishEvent(new DeliveryConfirmedEvent(42L));
        verify(lotFiller, times(1)).place(any());
    }

    @Test
    void aRefusedDemoCodePutsNothingIntoLots() {
        Delivery delivery = givenDeliveryWithCode();
        String code = confirmationCodes.issue(delivery, Instant.now());
        String demo = otherCode(code);
        String wrong = code.equals("222222") || demo.equals("222222") ? "333333" : "222222";
        useDemoCode(demo);

        for (int i = 0; i < 3; i++) {
            assertThrows(WrongCodeException.class, () -> service.confirmWithCode(42L, wrong));
        }
        assertThrows(IllegalStateException.class, () -> service.confirmWithCode(42L, demo));

        verify(lotFiller, never()).place(any());
    }

    @Test
    void memberCodeStillWorksNormallyWhenTheDemoCodeIsSet() {
        Delivery delivery = givenDeliveryWithCode();
        String code = confirmationCodes.issue(delivery, Instant.now());
        useDemoCode(otherCode(code));

        DeliveryResponse response = service.confirmWithCode(42L, code);

        assertEquals(ConfirmationStatus.CONFIRMED, delivery.getConfirmationStatus());
        assertEquals(null, response.resolutionNote());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void demoCodeIsAWrongCodeWhenNotSet() {
        Delivery delivery = givenDeliveryWithCode();
        String notTheCode = otherCode(confirmationCodes.issue(delivery, Instant.now()));

        WrongCodeException e = assertThrows(WrongCodeException.class,
                () -> service.confirmWithCode(42L, notTheCode));

        assertEquals("Wrong code. 2 tries left.", e.getMessage());
        assertEquals(ConfirmationStatus.PENDING, delivery.getConfirmationStatus());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void demoCodeKeepsTheThreeTryLimit() {
        Delivery delivery = givenDeliveryWithCode();
        String code = confirmationCodes.issue(delivery, Instant.now());
        String demo = otherCode(code);
        String wrong = code.equals("222222") || demo.equals("222222") ? "333333" : "222222";
        useDemoCode(demo);

        for (int i = 0; i < 3; i++) {
            assertThrows(WrongCodeException.class, () -> service.confirmWithCode(42L, wrong));
        }
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.confirmWithCode(42L, demo));

        assertEquals("Delivery RCT-7K2Q has no valid code. Send the member a new code.", e.getMessage());
        assertEquals(ConfirmationStatus.PENDING, delivery.getConfirmationStatus());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void demoCodeOnlyConfirmsAPendingDelivery() {
        Delivery delivery = givenDeliveryWithCode();
        String demo = otherCode(confirmationCodes.issue(delivery, Instant.now()));
        delivery.dispute(DisputeReason.WRONG_KG, null, Instant.now());
        useDemoCode(demo);

        assertThrows(IllegalStateException.class, () -> service.confirmWithCode(42L, demo));
        assertEquals(ConfirmationStatus.DISPUTED, delivery.getConfirmationStatus());
    }

    private void useDemoCode(String demo) {
        ReflectionTestUtils.setField(service, "confirmationCodes", new ConfirmationCodes(encoder, demo));
    }

    @Test
    void correctionStartsPendingAndGetsItsOwnCodeSms() {
        Delivery original = givenStoredDelivery("100.00");
        original.confirm(ConfirmationMethod.OTP, Instant.now());
        when(ledgerService.reverse(any(), any(), any())).thenReturn(reversalEntry("Weight"));

        service.correct(42L,
                new CorrectDeliveryRequest("Weight", 5L, 1L, new BigDecimal("90.00")), manager);
        Delivery corrected = lastSaved();

        assertEquals(ConfirmationStatus.PENDING, corrected.getConfirmationStatus());
        verify(eventPublisher).publishEvent(new DeliveryRecordedEvent(corrected.getId()));
    }

    // --- Sending a new code ---

    @Test
    void resendKillsTheOldCodeAndSendsANewOne() {
        Delivery delivery = givenDeliveryWithCode();
        String oldCode = confirmationCodes.issue(delivery, Instant.now());

        DeliveryResponse response = service.resendCode(42L, manager);

        assertEquals(null, delivery.getConfirmationCodeHash());
        assertThrows(IllegalStateException.class, () -> service.confirmWithCode(42L, oldCode));
        assertEquals(2, response.codeResendsLeft());
        verify(eventPublisher).publishEvent(new ConfirmationCodeResentEvent(42L, 1));
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertEquals(AuditAction.CONFIRMATION_CODE_RESENT, audit.getValue().getAction());
        assertEquals("Delivery RCT-7K2Q", audit.getValue().getDetails());
    }

    @Test
    void newCodeHasItsOwnThreeTries() {
        Delivery delivery = givenDeliveryWithCode();
        String code = confirmationCodes.issue(delivery, Instant.now());
        for (int i = 0; i < 3; i++) {
            assertThrows(WrongCodeException.class, () -> service.confirmWithCode(42L, otherCode(code)));
        }

        service.resendCode(42L, manager);
        String newCode = confirmationCodes.issue(delivery, Instant.now());
        service.confirmWithCode(42L, newCode);

        assertEquals(ConfirmationStatus.CONFIRMED, delivery.getConfirmationStatus());
    }

    @Test
    void aCodeCanBeSentAgainAtMostThreeTimes() {
        givenDeliveryWithCode();
        service.resendCode(42L, manager);
        service.resendCode(42L, manager);
        service.resendCode(42L, manager);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.resendCode(42L, manager));

        assertEquals("A new code was already sent 3 times for delivery RCT-7K2Q.", e.getMessage());
        verify(eventPublisher).publishEvent(new ConfirmationCodeResentEvent(42L, 3));
        verify(eventPublisher, never()).publishEvent(new ConfirmationCodeResentEvent(42L, 4));
    }

    @Test
    void noCodeIsSentWhenSmsAreTurnedOff() {
        givenDeliveryWithCode();
        setting.setSmsEnabled(false);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.resendCode(42L, manager));

        assertTrue(e.getMessage().startsWith("SMS are turned off, so no code can be sent."));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void noNewCodeForAConfirmedDelivery() {
        Delivery delivery = givenDeliveryWithCode();
        delivery.confirm(ConfirmationMethod.SMS, Instant.now());

        assertThrows(IllegalStateException.class, () -> service.resendCode(42L, manager));
    }

    @Test
    void noNewCodeForAReversedDelivery() {
        Delivery delivery = givenDeliveryWithCode();
        delivery.markReversed(reversalEntry("Gone"));

        assertThrows(IllegalStateException.class, () -> service.resendCode(42L, manager));
    }

    // --- Manager review when no code can reach the member ---

    @Test
    void withSmsOffTheManagerConfirmsAPendingDelivery() {
        Delivery delivery = givenDeliveryWithCode();
        setting.setSmsEnabled(false);

        service.reviewPending(42L, "  Weighed again with the member  ", manager);

        assertEquals(ConfirmationStatus.CONFIRMED, delivery.getConfirmationStatus());
        assertEquals(ConfirmationMethod.MANAGER_REVIEW, delivery.getConfirmationMethod());
        assertEquals("Weighed again with the member", delivery.getResolutionNote());
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertEquals(AuditAction.DELIVERY_REVIEWED, audit.getValue().getAction());
        assertEquals("Delivery RCT-7K2Q: Weighed again with the member", audit.getValue().getDetails());
        verify(lotFiller).place(delivery);
        verify(eventPublisher).publishEvent(new DeliveryConfirmedEvent(42L));
    }

    @Test
    void withSmsOnTheManagerConfirmsOnceAllNewCodesWereUsed() {
        Delivery delivery = givenDeliveryWithCode();
        service.resendCode(42L, manager);
        service.resendCode(42L, manager);
        service.resendCode(42L, manager);

        DeliveryResponse response = service.reviewPending(42L,
                "Member has no phone signal; weighed again together", manager);

        assertEquals(ConfirmationStatus.CONFIRMED, delivery.getConfirmationStatus());
        assertEquals(ConfirmationMethod.MANAGER_REVIEW, response.confirmationMethod());
        assertEquals("Checked by the manager", response.confirmationLabel());
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository, times(4)).save(audit.capture());
        assertEquals(AuditAction.DELIVERY_REVIEWED, audit.getAllValues().get(3).getAction());
    }

    @Test
    void withSmsOnReviewIsRefusedWhileNewCodesAreLeft() {
        Delivery delivery = givenDeliveryWithCode();
        service.resendCode(42L, manager);
        service.resendCode(42L, manager);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reviewPending(42L, "Checked", manager));

        assertEquals("SMS are turned on, so the member confirms with the code from the SMS. Send a new code if it "
                + "did not arrive (1 left), or review the delivery once it has been pending for 48 hours.",
                e.getMessage());
        assertEquals(ConfirmationStatus.PENDING, delivery.getConfirmationStatus());
        verify(eventPublisher, never()).publishEvent(any(DeliveryConfirmedEvent.class));
    }

    @Test
    void deliveryPendingForMoreThan48HoursCanBeReviewed() {
        Delivery delivery = givenDeliveryWithCode();
        ReflectionTestUtils.setField(delivery, "createdAt", Instant.now().minus(Duration.ofHours(49)));

        DeliveryResponse response = service.reviewPending(42L, "Member confirmed in person at the store", manager);

        assertEquals(ConfirmationStatus.CONFIRMED, delivery.getConfirmationStatus());
        assertEquals("Checked by the manager", response.confirmationLabel());
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertEquals(AuditAction.DELIVERY_REVIEWED, audit.getValue().getAction());
        assertEquals("Delivery RCT-7K2Q: Member confirmed in person at the store", audit.getValue().getDetails());
    }

    @Test
    void deliveryPendingForLessThan48HoursCannotBeReviewedWhileCodesAreLeft() {
        Delivery delivery = givenDeliveryWithCode();
        ReflectionTestUtils.setField(delivery, "createdAt", Instant.now().minus(Duration.ofHours(47)));

        assertThrows(IllegalStateException.class, () -> service.reviewPending(42L, "Checked", manager));
        assertEquals(ConfirmationStatus.PENDING, delivery.getConfirmationStatus());
    }

    @Test
    void overdueButDisputedDeliveryIsNotClosedByTheReview() {
        Delivery delivery = givenDeliveryWithCode();
        ReflectionTestUtils.setField(delivery, "createdAt", Instant.now().minus(Duration.ofHours(72)));
        delivery.dispute(DisputeReason.WRONG_KG, null, Instant.now());

        assertThrows(IllegalStateException.class, () -> service.reviewPending(42L, "Checked", manager));
        assertEquals(ConfirmationStatus.DISPUTED, delivery.getConfirmationStatus());
    }

    @Test
    void stuckButDisputedDeliveryIsNotClosedByTheReview() {
        Delivery delivery = givenDeliveryWithCode();
        service.resendCode(42L, manager);
        service.resendCode(42L, manager);
        service.resendCode(42L, manager);
        delivery.dispute(DisputeReason.NOT_MY_DELIVERY, null, Instant.now());

        assertThrows(IllegalStateException.class, () -> service.reviewPending(42L, "Checked", manager));
        assertEquals(ConfirmationStatus.DISPUTED, delivery.getConfirmationStatus());
    }

    @Test
    void withSmsOnTheMemberMustConfirm() {
        Delivery delivery = givenDeliveryWithCode();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.reviewPending(42L, "Checked", manager));

        assertTrue(e.getMessage().startsWith("SMS are turned on"));
        assertEquals(ConfirmationStatus.PENDING, delivery.getConfirmationStatus());
    }

    @Test
    void disputedDeliveryIsNotClosedByThePendingReview() {
        Delivery delivery = givenDeliveryWithCode();
        setting.setSmsEnabled(false);
        delivery.dispute(DisputeReason.WRONG_KG, null, Instant.now());

        assertThrows(IllegalStateException.class, () -> service.reviewPending(42L, "Checked", manager));
        assertEquals(ConfirmationStatus.DISPUTED, delivery.getConfirmationStatus());
    }

    @Test
    void reversedDeliveryCannotBeReviewed() {
        Delivery delivery = givenDeliveryWithCode();
        setting.setSmsEnabled(false);
        delivery.markReversed(reversalEntry("Gone"));

        assertThrows(IllegalStateException.class, () -> service.reviewPending(42L, "Checked", manager));
    }

    // --- Disputes ---

    @Test
    void confirmedDeliveryCanBeDisputed() {
        Delivery delivery = givenStoredDelivery("100.00");
        delivery.confirm(ConfirmationMethod.SMS, Instant.now());

        service.dispute(42L, DisputeReason.WRONG_KG, "  Was 80 kg, not 100  ");

        assertEquals(ConfirmationStatus.DISPUTED, delivery.getConfirmationStatus());
        assertEquals(DisputeReason.WRONG_KG, delivery.getDisputeReason());
        assertEquals("Was 80 kg, not 100", delivery.getDisputeNote());
        assertTrue(delivery.getDisputedAt() != null);
    }

    @Test
    void deliveryCannotBeDisputedTwice() {
        Delivery delivery = givenStoredDelivery("100.00");
        delivery.dispute(DisputeReason.OTHER, null, Instant.now());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.dispute(42L, DisputeReason.WRONG_GRADE, null));

        assertEquals("Delivery RCT-7K2Q is already disputed.", e.getMessage());
    }

    @Test
    void reversedDeliveryCannotBeDisputed() {
        Delivery delivery = givenStoredDelivery("100.00");
        delivery.markReversed(reversalEntry("Gone"));

        assertThrows(IllegalStateException.class, () -> service.dispute(42L, DisputeReason.OTHER, null));
    }

    @Test
    void checkedDisputeGoesBackToConfirmedByManagerReview() {
        Delivery delivery = givenStoredDelivery("100.00");
        delivery.dispute(DisputeReason.WRONG_KG, "Says 80 kg", Instant.now());

        service.resolveDispute(42L, "Scale receipt shows 100 kg");

        assertEquals(ConfirmationStatus.CONFIRMED, delivery.getConfirmationStatus());
        assertEquals(ConfirmationMethod.MANAGER_REVIEW, delivery.getConfirmationMethod());
        assertEquals("Scale receipt shows 100 kg", delivery.getResolutionNote());
        assertEquals(DisputeReason.WRONG_KG, delivery.getDisputeReason());
        verify(lotFiller).place(delivery);
        verify(eventPublisher).publishEvent(new DeliveryConfirmedEvent(42L));
    }

    @Test
    void onlyADisputedDeliveryCanBeResolved() {
        givenStoredDelivery("100.00");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.resolveDispute(42L, "Checked"));

        assertEquals("Delivery RCT-7K2Q is not disputed.", e.getMessage());
        verify(eventPublisher, never()).publishEvent(any(DeliveryConfirmedEvent.class));
    }

    @Test
    void reversingADisputedDeliveryClosesTheDispute() {
        Delivery delivery = givenStoredDelivery("100.00");
        delivery.dispute(DisputeReason.NOT_MY_DELIVERY, null, Instant.now());
        when(ledgerService.reverse(77L, "Recorded for the wrong member", manager))
                .thenReturn(reversalEntry("Recorded for the wrong member"));

        service.reverse(42L, "Recorded for the wrong member", manager);

        assertTrue(delivery.isReversed());
        assertEquals("Reversed: Recorded for the wrong member", delivery.getResolutionNote());
    }

    @Test
    void correctingADisputedDeliveryClosesTheDispute() {
        Delivery delivery = givenStoredDelivery("100.00");
        delivery.dispute(DisputeReason.WRONG_KG, "Was 90 kg", Instant.now());
        when(ledgerService.reverse(any(), any(), any())).thenReturn(reversalEntry("Weight"));

        service.correct(42L,
                new CorrectDeliveryRequest("Weight", 5L, 1L, new BigDecimal("90.00")), manager);
        Delivery corrected = lastSaved();

        assertEquals("Corrected by " + corrected.getReceiptCode() + ": Weight", delivery.getResolutionNote());
    }

    // --- SMS after the delivery is saved ---

    @Test
    void newDeliveryAnnouncesItselfForTheSms() {
        givenOpenLot();

        record("100");

        verify(eventPublisher).publishEvent(new DeliveryRecordedEvent(100L));
    }

    @Test
    void repeatedOfflineDeliveryDoesNotSendASecondSms() {
        Lot lot = givenOpenLot();
        when(deliveryRepository.findByClientUuid(CLIENT_UUID)).thenReturn(Optional.of(existingDelivery(lot)));

        record("100");

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void correctedDeliveryAlsoGetsAnSms() {
        givenStoredDelivery("100.00");
        when(ledgerService.reverse(any(), any(), any())).thenReturn(reversalEntry("Weight"));

        service.correct(42L,
                new CorrectDeliveryRequest("Weight", 5L, 1L, new BigDecimal("90.00")), manager);
        Delivery corrected = lastSaved();

        verify(eventPublisher).publishEvent(new DeliveryRecordedEvent(corrected.getId()));
    }

    // The delivery the service saved last, for example the new delivery of a correction.
    private Delivery lastSaved() {
        ArgumentCaptor<Delivery> saved = ArgumentCaptor.forClass(Delivery.class);
        verify(deliveryRepository, atLeastOnce()).save(saved.capture());
        return saved.getValue();
    }

    private Delivery givenDeliveryWithCode() {
        Delivery delivery = givenStoredDelivery("100.00");
        when(deliveryRepository.findForUpdateById(42L)).thenReturn(Optional.of(delivery));
        return delivery;
    }

    private static String otherCode(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    private RecordedDelivery recordAt(Instant deliveredAt) {
        return service.record(new DeliveryRequest(CLIENT_UUID, 5L, 1L, new BigDecimal("100"), deliveredAt), manager);
    }

    private Delivery existingDelivery(Lot lot) {
        Delivery existing = new Delivery();
        ReflectionTestUtils.setField(existing, "id", 42L);
        existing.setClientUuid(CLIENT_UUID);
        existing.setMember(member);
        existing.setGrade(lot.getGrade());
        existing.setLot(lot);
        existing.setQuantityKg(new BigDecimal("80.00"));
        existing.setDeductionAmount(400L);
        existing.setReceiptCode("RCT-7K2Q");
        return existing;
    }

    private RecordedDelivery record(String quantityKg) {
        return service.record(new DeliveryRequest(CLIENT_UUID, 5L, 1L, new BigDecimal(quantityKg), null), manager);
    }

    // --- Responses are built inside the transaction ---
    // Open-in-view is off, so a lazy lot or grade can only load while a @Transactional method runs. These tests
    // drive the real controller through a proxy that opens a "session" only for @Transactional calls, with a grade
    // that fails like a Hibernate proxy outside one. Building the response after the service returned (the 500
    // seen on confirm and resend-code) fails here the same way.

    @Test
    void lazyGradeReallyFailsOutsideATransaction() {
        Delivery delivery = givenLazyDelivery();

        assertThrows(LazyInitializationException.class, () -> DeliveryResponse.from(delivery));
    }

    @Test
    void confirmBuildsItsResponseInsideTheTransaction() {
        Delivery delivery = givenLazyDelivery();
        String code = confirmationCodes.issue(delivery, Instant.now());

        DeliveryResponse response = controller().confirm(42L, new ConfirmCodeRequest(code));

        assertEquals("Big", response.gradeName());
        assertEquals(ConfirmationStatus.CONFIRMED, response.confirmationStatus());
    }

    @Test
    void resendBuildsItsResponseInsideTheTransaction() {
        givenLazyDelivery();

        assertEquals("Big", controller().resendCode(42L, jwt()).gradeName());
    }

    @Test
    void reviewBuildsItsResponseInsideTheTransaction() {
        givenLazyDelivery();
        setting.setSmsEnabled(false);

        assertEquals("Big", controller().review(42L, new ManagerReviewRequest("Checked"), jwt()).gradeName());
    }

    @Test
    void disputeAndResolveBuildTheirResponsesInsideTheTransaction() {
        givenLazyDelivery();
        DeliveryController controller = controller();

        assertEquals("Big", controller.dispute(42L, new DisputeRequest(DisputeReason.WRONG_KG, null)).gradeName());
        assertEquals("Big", controller.resolveDispute(42L, new ManagerReviewRequest("Checked")).gradeName());
    }

    @Test
    void correctBuildsItsResponseInsideTheTransaction() {
        givenLazyDelivery();
        when(ledgerService.reverse(any(), any(), any())).thenReturn(reversalEntry("Weight"));

        assertEquals("Big", controller().correct(42L,
                new CorrectDeliveryRequest("Weight", 5L, 1L, new BigDecimal("90.00")), jwt()).gradeName());
    }

    @Test
    void reverseBuildsItsResponseInsideTheTransaction() {
        givenLazyDelivery();
        when(ledgerService.reverse(any(), any(), any())).thenReturn(reversalEntry("Gone"));

        assertEquals("Big", controller().reverse(42L, new ReverseRequest("Gone"), jwt()).gradeName());
    }

    private final AtomicBoolean inTransaction = new AtomicBoolean(false);

    // A stored delivery whose grade behaves like an uninitialised Hibernate proxy outside a transaction.
    private Delivery givenLazyDelivery() {
        Delivery delivery = givenDeliveryWithCode();
        Grade lazyGrade = spy(delivery.getGrade());
        doAnswer(invocation -> {
            if (!inTransaction.get()) {
                throw new LazyInitializationException("Could not initialize proxy [Grade#1] - no session");
            }
            return invocation.callRealMethod();
        }).when(lazyGrade).getName();
        delivery.setGrade(lazyGrade);
        return delivery;
    }

    // The real controller on top of the service, with @Transactional methods marking the session as open.
    private DeliveryController controller() {
        ProxyFactory factory = new ProxyFactory(service);
        factory.setProxyTargetClass(true);
        factory.addAdvice((MethodInterceptor) invocation -> {
            boolean opens = invocation.getMethod().isAnnotationPresent(Transactional.class) && !inTransaction.get();
            if (opens) {
                inTransaction.set(true);
            }
            try {
                return invocation.proceed();
            } finally {
                if (opens) {
                    inTransaction.set(false);
                }
            }
        });
        AuthService authService = mock(AuthService.class);
        when(authService.currentManager("manager@example.com")).thenReturn(manager);
        return new DeliveryController((DeliveryService) factory.getProxy(), authService);
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("token").header("alg", "HS256").subject("manager@example.com").build();
    }

    private LotPortion givenPortion(Delivery delivery, Lot lot, String kg, Long id) {
        LotPortion portion = new LotPortion(lot, delivery, new BigDecimal(kg));
        ReflectionTestUtils.setField(portion, "id", id);
        List<LotPortion> portions = new java.util.ArrayList<>(lotPortionRepository.findByDeliveryIdOrderById(42L));
        portions.add(portion);
        when(lotPortionRepository.findByDeliveryIdOrderById(42L)).thenReturn(portions);
        return portion;
    }

    private Lot fixedLot(Long id, String code, LotStatus status) {
        Lot lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", id);
        lot.setLotCode(code);
        lot.setGrade(grade);
        lot.setStatus(status);
        lot.setCapacityKg(new BigDecimal("100.00"));
        when(lotRepository.findForUpdateById(id)).thenReturn(Optional.of(lot));
        return lot;
    }

    // A lot from before fixed-size lots, which deliveries of that time are in.
    private Lot givenOpenLot() {
        Lot lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", 3L);
        lot.setLotCode("A-2026-01");
        lot.setGrade(grade);
        return lot;
    }
}
