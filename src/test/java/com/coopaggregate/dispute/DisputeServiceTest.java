package com.coopaggregate.dispute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.delivery.DisputeReason;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.ledger.LedgerEntry;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.member.Member;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

class DisputeServiceTest {

    private DeliveryRepository deliveryRepository;
    private DisputeService service;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(DeliveryRepository.class);
        service = new DisputeService(deliveryRepository);
        when(deliveryRepository.findByCorrectsIdIn(any())).thenReturn(List.of());
        when(deliveryRepository.countByConfirmationStatusAndReversedAtIsNull(ConfirmationStatus.DISPUTED))
                .thenReturn(2L);
        when(deliveryRepository.sumOpenDisputedKg()).thenReturn(new BigDecimal("370.50"));
        when(deliveryRepository.countSettledDisputes()).thenReturn(5L);
    }

    @Test
    void openDisputeShowsTheDeliveryTheReasonAndHowLongItHasBeenOpen() {
        Delivery delivery = disputed(42L, "RCT-7K2Q", Instant.now().minus(Duration.ofDays(3).plusHours(2)));
        givenPage(delivery);

        DisputesResponse response = service.list(null, null, 0, 15);

        DisputeItem item = response.disputes().content().get(0);
        assertEquals(42L, item.deliveryId());
        assertEquals("RCT-7K2Q", item.receiptCode());
        assertEquals(5L, item.memberId());
        assertEquals("Uwimana Claudine", item.memberName());
        assertEquals("M-0005", item.memberNumber());
        assertEquals("Big", item.gradeName());
        assertEquals("A-2026-01", item.lotCode());
        assertEquals(new BigDecimal("120.50"), item.quantityKg());
        assertEquals(DisputeReason.WRONG_KG, item.reason());
        assertEquals("It was 100 kg", item.note());
        assertEquals(3, item.daysOpen());
        assertTrue(item.open());
        assertNull(item.outcome());
        assertNull(item.settledAt());
        assertNull(item.resolutionNote());
    }

    @Test
    void disputeOpenedTodayHasBeenOpenZeroDays() {
        givenPage(disputed(42L, "RCT-7K2Q", Instant.now().minus(Duration.ofHours(5))));

        assertEquals(0, service.list("open", null, 0, 15).disputes().content().get(0).daysOpen());
    }

    @Test
    void totalsCoverAllDisputesAndMatchTheDashboardCount() {
        givenPage();

        DisputesResponse response = service.list("open", "kalisa", 0, 15);

        assertEquals(2L, response.openDisputes());
        assertEquals(new BigDecimal("370.50"), response.openKg());
        assertEquals(5L, response.settledDisputes());
    }

    @Test
    void disputeFoundCorrectIsSettledAsResolved() {
        Instant disputedAt = Instant.now().minus(Duration.ofDays(6));
        Delivery delivery = disputed(42L, "RCT-7K2Q", disputedAt);
        delivery.confirmByManagerReview("Scale receipt shows 120.5 kg", disputedAt.plus(Duration.ofDays(2)));
        givenPage(delivery);

        DisputeItem item = service.list("settled", null, 0, 15).disputes().content().get(0);

        assertFalse(item.open());
        assertEquals(DisputeOutcome.RESOLVED, item.outcome());
        assertEquals(disputedAt.plus(Duration.ofDays(2)), item.settledAt());
        assertEquals(2, item.daysOpen());
        assertEquals("Scale receipt shows 120.5 kg", item.resolutionNote());
        assertEquals(DisputeReason.WRONG_KG, item.reason());
    }

    @Test
    void reversedDisputeIsSettledAsReversed() {
        Instant disputedAt = Instant.now().minus(Duration.ofDays(4));
        Delivery delivery = disputed(42L, "RCT-7K2Q", disputedAt);
        delivery.markReversed(reversal(disputedAt.plus(Duration.ofDays(1))));
        delivery.setResolutionNote("Reversed: not this member");
        givenPage(delivery);

        DisputeItem item = service.list("SETTLED", null, 0, 15).disputes().content().get(0);

        assertEquals(DisputeOutcome.REVERSED, item.outcome());
        assertEquals(disputedAt.plus(Duration.ofDays(1)), item.settledAt());
        assertEquals(1, item.daysOpen());
        assertEquals("Reversed: not this member", item.resolutionNote());
        assertNull(item.correctedById());
    }

    @Test
    void correctedDisputeIsSettledAsCorrectedWithTheNewDelivery() {
        Instant disputedAt = Instant.now().minus(Duration.ofDays(2));
        Delivery delivery = disputed(42L, "RCT-7K2Q", disputedAt);
        delivery.markReversed(reversal(disputedAt.plus(Duration.ofHours(3))));
        delivery.setResolutionNote("Corrected by RCT-9MNP: weight");
        Delivery corrected = new Delivery();
        ReflectionTestUtils.setField(corrected, "id", 43L);
        corrected.setCorrects(delivery);
        when(deliveryRepository.findByCorrectsIdIn(List.of(42L))).thenReturn(List.of(corrected));
        givenPage(delivery);

        DisputeItem item = service.list("settled", null, 0, 15).disputes().content().get(0);

        assertEquals(DisputeOutcome.CORRECTED, item.outcome());
        assertEquals(43L, item.correctedById());
        assertEquals(0, item.daysOpen());
    }

    @Test
    @SuppressWarnings("unchecked")
    void pageIsNewestDisputeFirstFifteenByDefaultAndCappedAt100() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(deliveryRepository.findAll(any(Specification.class), pageable.capture())).thenReturn(new PageImpl<>(List.of()));

        service.list(null, null, -2, 500);

        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(100, pageable.getValue().getPageSize());
        assertEquals("disputedAt: DESC,id: DESC", pageable.getValue().getSort().toString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownStatusFilterIsRefused() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.list("closed", null, 0, 15));

        assertEquals("status must be open or settled.", e.getMessage());
        verify(deliveryRepository, never()).findAll(any(Specification.class), any(Pageable.class));
    }

    @Test
    void responseIsBuiltInsideAReadOnlyTransaction() throws Exception {
        Transactional transactional = DisputeService.class
                .getMethod("list", String.class, String.class, int.class, int.class)
                .getAnnotation(Transactional.class);

        assertTrue(transactional.readOnly());
    }

    @Test
    @SuppressWarnings("unchecked")
    void openMeansStillDisputedAndNotReversed() {
        Criteria criteria = new Criteria();

        DisputeService.matching(true, null).toPredicate(criteria.root, criteria.query, criteria.cb);

        verify(criteria.cb).equal(criteria.path, ConfirmationStatus.DISPUTED);
        verify(criteria.cb).isNull(criteria.path);
        verify(criteria.root).get("confirmationStatus");
        verify(criteria.root).get("reversedAt");
        verify(criteria.cb, never()).isNotNull(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void settledMeansWasDisputedAndNoLongerDisputedOrReversed() {
        Criteria criteria = new Criteria();

        DisputeService.matching(false, null).toPredicate(criteria.root, criteria.query, criteria.cb);

        verify(criteria.root).get("disputedAt");
        verify(criteria.cb).notEqual(criteria.path, ConfirmationStatus.DISPUTED);
        verify(criteria.cb, org.mockito.Mockito.times(2)).isNotNull(criteria.path);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchLooksAtReceiptCodeAndMemberNameAndNumber() {
        Criteria criteria = new Criteria();

        DisputeService.matching(true, " RCT-7k ").toPredicate(criteria.root, criteria.query, criteria.cb);

        verify(criteria.root).get("receiptCode");
        verify(criteria.path).get("fullName");
        verify(criteria.path).get("memberCode");
        verify(criteria.cb, org.mockito.Mockito.times(3)).like(any(Expression.class), org.mockito.ArgumentMatchers.eq("%rct-7k%"));
    }

    /** Mocks for checking a Specification without a database. Every path is the same mock. */
    @SuppressWarnings("unchecked")
    private static final class Criteria {
        final Root<Delivery> root = mock(Root.class);
        final CriteriaQuery<?> query = mock(CriteriaQuery.class);
        final CriteriaBuilder cb = mock(CriteriaBuilder.class);
        final Path<Object> path = mock(Path.class);

        Criteria() {
            when(root.get(anyString())).thenReturn(path);
            when(path.get(anyString())).thenReturn(path);
            when(cb.lower(any(Expression.class))).thenReturn(mock(Expression.class));
            when(cb.like(any(Expression.class), anyString())).thenReturn(mock(Predicate.class));
            when(cb.or(any(Predicate[].class))).thenReturn(mock(Predicate.class));
            when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));
            when(cb.equal(any(), any(Object.class))).thenReturn(mock(Predicate.class));
            when(cb.notEqual(any(), any(Object.class))).thenReturn(mock(Predicate.class));
            when(cb.isNull(any())).thenReturn(mock(Predicate.class));
            when(cb.isNotNull(any())).thenReturn(mock(Predicate.class));
        }
    }

    @SuppressWarnings("unchecked")
    private void givenPage(Delivery... deliveries) {
        when(deliveryRepository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(deliveries)));
    }

    private static Delivery disputed(Long id, String receiptCode, Instant disputedAt) {
        Member member = new Member();
        ReflectionTestUtils.setField(member, "id", 5L);
        member.setFullName("Uwimana Claudine");
        member.setMemberCode("M-0005");
        Grade grade = new Grade();
        grade.setName("Big");
        Lot lot = new Lot();
        lot.setLotCode("A-2026-01");
        lot.setGrade(grade);
        Delivery delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", id);
        delivery.setReceiptCode(receiptCode);
        delivery.setMember(member);
        delivery.setLot(lot);
        delivery.setQuantityKg(new BigDecimal("120.50"));
        delivery.dispute(DisputeReason.WRONG_KG, "It was 100 kg", disputedAt);
        return delivery;
    }

    private static LedgerEntry reversal(Instant at) {
        LedgerEntry reversal = new LedgerEntry();
        reversal.setEntryDate(at);
        reversal.setReason("reversed");
        return reversal;
    }
}
