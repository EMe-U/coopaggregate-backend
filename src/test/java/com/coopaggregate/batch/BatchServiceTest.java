package com.coopaggregate.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;

import jakarta.persistence.EntityNotFoundException;

class BatchServiceTest {

    private BatchRepository batchRepository;
    private MemberRepository memberRepository;
    private GradeRepository gradeRepository;
    private Member member;
    private Grade grade;
    private BatchService service;

    @BeforeEach
    void setUp() {
        batchRepository = mock(BatchRepository.class);
        memberRepository = mock(MemberRepository.class);
        gradeRepository = mock(GradeRepository.class);
        member = new Member();
        ReflectionTestUtils.setField(member, "id", 5L);
        member.setMemberCode("M-0005");
        member.setFullName("Uwimana Claudine");
        when(memberRepository.findById(5L)).thenReturn(Optional.of(member));
        grade = new Grade();
        ReflectionTestUtils.setField(grade, "id", 1L);
        grade.setName("Big");
        when(gradeRepository.findById(1L)).thenReturn(Optional.of(grade));
        when(batchRepository.findMemberBatches(anyLong(), anyBoolean(), any())).thenReturn(new PageImpl<>(List.of()));
        when(batchRepository.findGradeBatchesWithKgLeft(anyLong(), any())).thenReturn(new PageImpl<>(List.of()));
        when(batchRepository.memberTotals(5L)).thenReturn(new BatchTotals(BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO));
    }

    @Test
    void memberBatchesShowReceivedSoldLostRemainingAndTheirTotals() {
        Delivery batch = batch(42L, "RCT-7K2Q", "120.50", "100.00", "0.50", "20.00");
        when(batchRepository.findMemberBatches(eq(5L), eq(false), any())).thenReturn(new PageImpl<>(List.of(batch)));
        when(batchRepository.memberTotals(5L)).thenReturn(new BatchTotals(new BigDecimal("320.50"),
                new BigDecimal("100.00"), new BigDecimal("0.50"), new BigDecimal("220.00")));

        MemberBatchesResponse response = service().memberBatches(5L, null, 0, 20);

        assertEquals("M-0005", response.memberNumber());
        assertEquals("Uwimana Claudine", response.memberName());
        assertEquals(new BatchTotals(new BigDecimal("320.50"), new BigDecimal("100.00"), new BigDecimal("0.50"),
                new BigDecimal("220.00")), response.totals());
        assertEquals(new BatchResponse(42L, "RCT-7K2Q", 5L, "M-0005", "Uwimana Claudine", "Big", 3L, "A-2026-01",
                LotStatus.CLOSED, Instant.parse("2026-10-19T08:00:00Z"), new BigDecimal("120.50"),
                new BigDecimal("100.00"), new BigDecimal("0.50"), new BigDecimal("20.00"), ConfirmationStatus.PENDING),
                response.batches().content().get(0));
    }

    @Test
    void unsoldShowsOnlyBatchesWithKgLeft() {
        service().memberBatches(5L, "UNSOLD", 0, 20);
        service().memberBatches(5L, "all", 0, 20);

        verify(batchRepository).findMemberBatches(eq(5L), eq(true), any());
        verify(batchRepository).findMemberBatches(eq(5L), eq(false), any());
    }

    @Test
    void unknownStatusIsRefused() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service().memberBatches(5L, "sold", 0, 20));

        assertEquals("status must be all or unsold.", e.getMessage());
        verify(batchRepository, never()).findMemberBatches(anyLong(), anyBoolean(), any());
    }

    @Test
    void unknownMemberOrGradeIsNotFound() {
        assertThrows(EntityNotFoundException.class, () -> service().memberBatches(99L, null, 0, 20));
        assertThrows(EntityNotFoundException.class, () -> service().gradeBatches(99L, 0, 20));
    }

    @Test
    void gradeBatchesAreInFifoOrderWithTheGradesRemainingKg() {
        Delivery batch = batch(42L, "RCT-7K2Q", "120.50", "0", "0", "120.50");
        when(batchRepository.findGradeBatchesWithKgLeft(eq(1L), any())).thenReturn(new PageImpl<>(List.of(batch)));
        when(batchRepository.gradeRemainingKg(1L)).thenReturn(new BigDecimal("870.25"));

        GradeBatchesResponse response = service().gradeBatches(1L, 0, 20);

        assertEquals("Big", response.gradeName());
        assertEquals(new BigDecimal("870.25"), response.remainingKg());
        assertEquals("RCT-7K2Q", response.batches().content().get(0).receiptCode());
    }

    @Test
    void pagesAreCappedAt100() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        service().gradeBatches(1L, -3, 500);

        verify(batchRepository).findGradeBatchesWithKgLeft(eq(1L), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(100, pageable.getValue().getPageSize());
    }

    @Test
    void responsesAreBuiltInsideReadOnlyTransactions() throws Exception {
        assertTrue(BatchService.class.getMethod("memberBatches", Long.class, String.class, int.class, int.class)
                .getAnnotation(Transactional.class).readOnly());
        assertTrue(BatchService.class.getMethod("gradeBatches", Long.class, int.class, int.class)
                .getAnnotation(Transactional.class).readOnly());
    }

    private BatchService service() {
        return new BatchService(batchRepository, memberRepository, gradeRepository);
    }

    // The database fills remainingKg; here it is set the way the database would.
    private Delivery batch(Long id, String receiptCode, String received, String sold, String lost, String remaining) {
        Lot lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", 3L);
        lot.setLotCode("A-2026-01");
        lot.setGrade(grade);
        lot.setStatus(LotStatus.CLOSED);
        Delivery delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", id);
        delivery.setReceiptCode(receiptCode);
        delivery.setMember(member);
        delivery.setLot(lot);
        delivery.setDeliveredAt(Instant.parse("2026-10-19T08:00:00Z"));
        delivery.setQuantityKg(new BigDecimal(received));
        ReflectionTestUtils.setField(delivery, "soldKg", new BigDecimal(sold).setScale(2));
        ReflectionTestUtils.setField(delivery, "lostKg", new BigDecimal(lost).setScale(2));
        ReflectionTestUtils.setField(delivery, "remainingKg", new BigDecimal(remaining));
        return delivery;
    }
}
