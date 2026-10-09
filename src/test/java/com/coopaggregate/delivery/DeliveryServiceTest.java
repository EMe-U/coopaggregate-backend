package com.coopaggregate.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

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
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;
import com.coopaggregate.member.MemberStatus;
import com.coopaggregate.setting.Setting;
import com.coopaggregate.setting.SettingRepository;

class DeliveryServiceTest {

    private static final UUID CLIENT_UUID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7");

    private DeliveryRepository deliveryRepository;
    private MemberRepository memberRepository;
    private LotRepository lotRepository;
    private LedgerService ledgerService;
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
        member.setMemberCode("MEM-0005");
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
        service = new DeliveryService(deliveryRepository, memberRepository, gradeRepository, settingRepository,
                new LotService(lotRepository), ledgerService);
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

        assertEquals("Member MEM-0005 is inactive. Activate the member before recording a delivery.", e.getMessage());
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
                "Delivery " + result.delivery().receiptCode() + " from MEM-0005", manager);
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
        Instant offlineTime = Instant.parse("2026-10-08T06:15:00Z");

        RecordedDelivery result = service.record(
                new DeliveryRequest(CLIENT_UUID, 5L, 1L, new BigDecimal("100"), offlineTime), manager);

        assertEquals(offlineTime, result.delivery().deliveredAt());
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
