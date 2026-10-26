package com.coopaggregate.buyer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.common.PageResponse;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.setting.SettingRepository;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

/** The manager's side of buyer requests: the list, status changes and turning a request into a buyer. */
class BuyerRequestManagerTest {

    private BuyerRequestRepository requestRepository;
    private BuyerRepository buyerRepository;
    private AuditLogRepository auditLogRepository;
    private Grade big;
    private BuyerRequest request;
    private BuyerRequestService service;

    @BeforeEach
    void setUp() {
        requestRepository = mock(BuyerRequestRepository.class);
        buyerRepository = mock(BuyerRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        service = new BuyerRequestService(requestRepository, mock(GradeRepository.class),
                mock(SettingRepository.class), buyerRepository, auditLogRepository);

        big = new Grade();
        big.setCode("A");
        big.setName("Big");
        request = new BuyerRequest();
        ReflectionTestUtils.setField(request, "id", 5L);
        request.setReferenceCode("BR-2026-7K2Q");
        request.setGrade(big);
        request.setQuantityKg(new BigDecimal("250.50"));
        request.setRequesterName("Kalisa Traders");
        request.setRequesterPhone("+250788123456");
        request.setRequesterEmail("kalisa@example.com");
        request.setMessage("Pick up on Friday");
        when(requestRepository.findForUpdateById(5L)).thenReturn(Optional.of(request));
        when(requestRepository.findById(5L)).thenReturn(Optional.of(request));
    }

    @Test
    void statusChangeIsSavedWithTheMessageAndWrittenToTheHistoryWithoutIt() {
        Manager manager = new Manager();

        BuyerRequestDetail detail = service.changeStatus(5L,
                new BuyerRequestStatusUpdate(BuyerRequestStatus.ACCEPTED, " Call me on 0788000111 "), manager);

        assertEquals(BuyerRequestStatus.ACCEPTED, detail.status());
        assertEquals("Call me on 0788000111", detail.publicMessage());
        assertTrue(request.getUpdatedAt() != null);
        AuditLog audit = savedAudit();
        assertEquals(AuditAction.BUYER_REQUEST_STATUS_CHANGED, audit.getAction());
        assertEquals(manager, audit.getManager());
        assertEquals("BR-2026-7K2Q: PENDING -> ACCEPTED; public message changed", audit.getDetails());
        assertFalse(audit.getDetails().contains("0788000111"));
    }

    @Test
    void statusCanChangeWithoutAMessageAndKeepsTheEarlierOne() {
        request.setPublicMessage("We are checking stock.");

        service.changeStatus(5L, new BuyerRequestStatusUpdate(BuyerRequestStatus.REJECTED, null), new Manager());

        assertEquals("We are checking stock.", request.getPublicMessage());
        assertEquals("BR-2026-7K2Q: PENDING -> REJECTED", savedAudit().getDetails());
    }

    @Test
    void sameStatusWithANewMessageIsAllowed() {
        request.setStatus(BuyerRequestStatus.ACCEPTED);

        service.changeStatus(5L, new BuyerRequestStatusUpdate(BuyerRequestStatus.ACCEPTED, "Pickup is Friday."),
                new Manager());

        assertEquals("Pickup is Friday.", request.getPublicMessage());
    }

    @Test
    void sameStatusWithNothingNewIsRefusedAndNotRecorded() {
        request.setStatus(BuyerRequestStatus.ACCEPTED);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.changeStatus(5L,
                new BuyerRequestStatusUpdate(BuyerRequestStatus.ACCEPTED, null), new Manager()));

        assertTrue(e.getMessage().startsWith("Request BR-2026-7K2Q is already ACCEPTED."));
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void unknownRequestIsNotFound() {
        assertThrows(EntityNotFoundException.class, () -> service.changeStatus(99L,
                new BuyerRequestStatusUpdate(BuyerRequestStatus.ACCEPTED, null), new Manager()));
        assertThrows(EntityNotFoundException.class, () -> service.get(99L));
        assertThrows(EntityNotFoundException.class, () -> service.convertToBuyer(99L, new Manager()));
    }

    @Test
    void detailShowsTheContactDetailsAndNotes() {
        BuyerRequestDetail detail = service.get(5L);

        assertEquals("BR-2026-7K2Q", detail.code());
        assertEquals("A", detail.gradeCode());
        assertEquals("Kalisa Traders", detail.buyerName());
        assertEquals("+250788123456", detail.buyerPhone());
        assertEquals("kalisa@example.com", detail.buyerEmail());
        assertEquals("Pick up on Friday", detail.note());
        assertNull(detail.buyerId());
    }

    @Test
    void conversionCreatesABuyerFromTheRequestAndRecordsIt() {
        when(buyerRepository.findByPhone("+250788123456")).thenReturn(Optional.empty());
        when(buyerRepository.save(any(Buyer.class))).thenAnswer(invocation -> {
            Buyer buyer = invocation.getArgument(0);
            ReflectionTestUtils.setField(buyer, "id", 12L);
            return buyer;
        });

        BuyerRequestDetail detail = service.convertToBuyer(5L, new Manager());

        assertEquals(12L, detail.buyerId());
        assertEquals("Kalisa Traders", request.getBuyer().getName());
        assertEquals("+250788123456", request.getBuyer().getPhone());
        assertEquals("kalisa@example.com", request.getBuyer().getEmail());
        AuditLog audit = savedAudit();
        assertEquals(AuditAction.BUYER_REQUEST_CONVERTED, audit.getAction());
        assertEquals("BR-2026-7K2Q: new buyer record created", audit.getDetails());
    }

    @Test
    void conversionLinksToTheBuyerWhoAlreadyHasThePhoneNumber() {
        Buyer known = new Buyer();
        ReflectionTestUtils.setField(known, "id", 3L);
        known.setName("Kalisa");
        when(buyerRepository.findByPhone("+250788123456")).thenReturn(Optional.of(known));

        BuyerRequestDetail detail = service.convertToBuyer(5L, new Manager());

        assertEquals(3L, detail.buyerId());
        assertEquals(known, request.getBuyer());
        verify(buyerRepository, never()).save(any());
        assertEquals("BR-2026-7K2Q: linked to an existing buyer", savedAudit().getDetails());
    }

    @Test
    void requestCanOnlyBeConvertedOnce() {
        request.setBuyer(new Buyer());

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.convertToBuyer(5L, new Manager()));

        assertEquals("Request BR-2026-7K2Q already has a buyer.", e.getMessage());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listIsNewestFirstAndCappedAt100() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(requestRepository.findAll(any(Specification.class), pageable.capture()))
                .thenReturn(new PageImpl<>(List.of(request)));

        PageResponse<BuyerRequestListItem> page = service.list(BuyerRequestStatus.PENDING, "kalisa", -1, 500);

        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(100, pageable.getValue().getPageSize());
        assertEquals("createdAt: DESC,id: DESC", pageable.getValue().getSort().toString());
        BuyerRequestListItem item = page.content().get(0);
        assertEquals("BR-2026-7K2Q", item.code());
        assertEquals("Big", item.gradeName());
        assertEquals("Kalisa Traders", item.buyerName());
        assertFalse(item.converted());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchLooksAtCodeNameAndPhoneAndStatusIsAnExactFilter() {
        Root<BuyerRequest> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Path<Object> path = mock(Path.class);
        when(root.get(anyString())).thenReturn(path);
        when(cb.lower(any(Expression.class))).thenReturn(mock(Expression.class));
        when(cb.like(any(Expression.class), anyString())).thenReturn(mock(Predicate.class));
        when(cb.or(any(Predicate[].class))).thenReturn(mock(Predicate.class));
        when(cb.equal(any(), any(Object.class))).thenReturn(mock(Predicate.class));
        when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));

        BuyerRequestService.matching(BuyerRequestStatus.ACCEPTED, "0788123456").toPredicate(root, query, cb);

        verify(root).get("status");
        verify(root).get("referenceCode");
        verify(root).get("requesterName");
        verify(root).get("requesterPhone");
        verify(cb).like(any(Expression.class), eq("%+250788123456%"));
    }

    private AuditLog savedAudit() {
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        return audit.getValue();
    }
}
