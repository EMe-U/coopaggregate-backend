package com.coopaggregate.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.member.Member;
import com.coopaggregate.setting.Setting;
import com.coopaggregate.setting.SettingRepository;

import jakarta.persistence.EntityNotFoundException;

class SalePreviewServiceTest {

    private BatchRepository batchRepository;
    private GradeRepository gradeRepository;
    private Grade grade;
    private SalePreviewService service;

    @BeforeEach
    void setUp() {
        batchRepository = mock(BatchRepository.class);
        gradeRepository = mock(GradeRepository.class);
        SettingRepository settingRepository = mock(SettingRepository.class);
        grade = new Grade();
        ReflectionTestUtils.setField(grade, "id", 1L);
        grade.setName("Big");
        when(gradeRepository.findById(1L)).thenReturn(Optional.of(grade));
        when(settingRepository.findById(1L)).thenReturn(Optional.of(new Setting()));
        when(batchRepository.findSellableBatches(1L)).thenReturn(List.of(
                batch(42L, "RCT-0042", "A-2026-01", "Uwimana Claudine", "100.00"),
                batch(43L, "RCT-0043", "A-2026-02", "Habimana Jean", "200.00")));
        when(batchRepository.gradeRemainingKgWithStatus(1L, ConfirmationStatus.PENDING))
                .thenReturn(new BigDecimal("80.00"));
        when(batchRepository.gradeRemainingKgWithStatus(1L, ConfirmationStatus.DISPUTED))
                .thenReturn(new BigDecimal("20.00"));
        service = new SalePreviewService(batchRepository, gradeRepository, settingRepository);
    }

    @Test
    void previewShowsTheBatchesUsedOldestFirstAndEachMembersCredit() {
        SalePreviewResponse preview = service.preview(1L, new BigDecimal("150.00"), 300L, null);

        assertTrue(preview.enough());
        assertNull(preview.message());
        assertEquals(5L, preview.deductionPerKg(), "the deduction comes from the settings");
        assertEquals(new BigDecimal("300.00"), preview.sellableKg());
        assertEquals(new BigDecimal("80.00"), preview.waitingConfirmationKg());
        assertEquals(new BigDecimal("20.00"), preview.disputedKg());
        assertEquals(45_000L, preview.totalAmountRwf());
        assertEquals(750L, preview.totalDeductionRwf());
        assertEquals(44_250L, preview.totalNetRwf());
        assertEquals(List.of(
                new SalePreviewResponse.Line(42L, "RCT-0042", 3L, "A-2026-01", 5L, "M-0005", "Uwimana Claudine",
                        Instant.parse("2026-10-01T08:00:00Z"), new BigDecimal("100.00"), new BigDecimal("0.00"),
                        30_000L, 500L, 29_500L),
                new SalePreviewResponse.Line(43L, "RCT-0043", 3L, "A-2026-02", 5L, "M-0005", "Habimana Jean",
                        Instant.parse("2026-10-01T08:00:00Z"), new BigDecimal("50.00"), new BigDecimal("150.00"),
                        15_000L, 250L, 14_750L)), preview.batches());
    }

    @Test
    void notEnoughKgIsShownNotRefused() {
        SalePreviewResponse preview = service.preview(1L, new BigDecimal("400.00"), 300L, List.of());

        assertFalse(preview.enough());
        assertEquals("Only 300.00 kg of Big can be sold now, but 400.00 kg were asked for. 80.00 kg are waiting for "
                + "their members to confirm them.", preview.message());
        assertEquals(List.of(), preview.batches());
    }

    @Test
    void skippedBatchesAreListed() {
        SalePreviewResponse preview = service.preview(1L, new BigDecimal("150.00"), 300L, List.of(42L));

        assertEquals(List.of(43L), preview.batches().stream().map(SalePreviewResponse.Line::deliveryId).toList());
        assertEquals(List.of(new SalePreviewResponse.Skipped(42L, "RCT-0042", "A-2026-01", "Uwimana Claudine",
                Instant.parse("2026-10-01T08:00:00Z"), new BigDecimal("100.00"))), preview.skipped());
    }

    @Test
    void aPriceBelowTheGradesFloorIsFlagged() {
        grade.setFloorPricePerKg(320L);

        assertTrue(service.preview(1L, new BigDecimal("10.00"), 300L, null).belowFloorPrice());
        assertFalse(service.preview(1L, new BigDecimal("10.00"), 320L, null).belowFloorPrice());
    }

    @Test
    void quantityAndPriceAreChecked() {
        assertEquals("quantityKg is required and must be more than 0.", assertThrows(BadRequestException.class,
                () -> service.preview(1L, null, 300L, null)).getMessage());
        assertEquals("quantityKg is required and must be more than 0.", assertThrows(BadRequestException.class,
                () -> service.preview(1L, new BigDecimal("0"), 300L, null)).getMessage());
        assertEquals("quantityKg must have at most 2 decimals and 10 digits before the point.",
                assertThrows(BadRequestException.class,
                        () -> service.preview(1L, new BigDecimal("1.005"), 300L, null)).getMessage());
        assertEquals("pricePerKg is required and must be more than 0.", assertThrows(BadRequestException.class,
                () -> service.preview(1L, BigDecimal.TEN, null, null)).getMessage());
        assertEquals("pricePerKg is required and must be more than 0.", assertThrows(BadRequestException.class,
                () -> service.preview(1L, BigDecimal.TEN, 0L, null)).getMessage());
        verify(batchRepository, never()).findSellableBatches(anyLong());
    }

    @Test
    void trailingZerosAreNotDecimals() {
        assertTrue(service.preview(1L, new BigDecimal("10.000"), 300L, null).enough());
    }

    @Test
    void unknownGradeIsNotFound() {
        assertThrows(EntityNotFoundException.class, () -> service.preview(99L, BigDecimal.TEN, 300L, null));
    }

    @Test
    void previewOnlyReads() throws Exception {
        service.preview(1L, new BigDecimal("150.00"), 300L, null);

        verify(batchRepository).findSellableBatches(1L);
        verify(batchRepository).gradeRemainingKgWithStatus(1L, ConfirmationStatus.PENDING);
        verify(batchRepository).gradeRemainingKgWithStatus(1L, ConfirmationStatus.DISPUTED);
        verifyNoMoreInteractions(batchRepository);
        assertTrue(SalePreviewService.class
                .getMethod("preview", Long.class, BigDecimal.class, Long.class, List.class)
                .getAnnotation(Transactional.class).readOnly());
    }

    private static Delivery batch(Long id, String receiptCode, String lotCode, String memberName, String remaining) {
        Lot lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", 3L);
        lot.setLotCode(lotCode);
        Member member = new Member();
        ReflectionTestUtils.setField(member, "id", 5L);
        member.setMemberCode("M-0005");
        member.setFullName(memberName);
        Delivery delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", id);
        delivery.setReceiptCode(receiptCode);
        delivery.setLot(lot);
        delivery.setMember(member);
        delivery.setDeliveredAt(Instant.parse("2026-10-01T08:00:00Z"));
        delivery.setQuantityKg(new BigDecimal(remaining));
        ReflectionTestUtils.setField(delivery, "remainingKg", new BigDecimal(remaining));
        return delivery;
    }
}
