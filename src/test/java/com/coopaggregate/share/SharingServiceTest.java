package com.coopaggregate.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.delivery.DisputeReason;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotQuantities;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;

class SharingServiceTest {

    private LotShareRepository lotShareRepository;
    private LedgerService ledgerService;
    private DeliveryRepository deliveryRepository;
    private Lot lot;
    private Manager manager;
    private SharingService service;

    @BeforeEach
    void setUp() {
        lotShareRepository = mock(LotShareRepository.class);
        LotRepository lotRepository = mock(LotRepository.class);
        LotService lotService = mock(LotService.class);
        MemberRepository memberRepository = mock(MemberRepository.class);
        ledgerService = mock(LedgerService.class);

        Grade grade = new Grade();
        grade.setName("Big");
        lot = new Lot();
        ReflectionTestUtils.setField(lot, "id", 3L);
        lot.setLotCode("A-2026-01");
        lot.setGrade(grade);
        lot.setStatus(LotStatus.SOLD);
        when(lotRepository.findById(3L)).thenReturn(Optional.of(lot));
        when(lotRepository.findForUpdateById(3L)).thenReturn(Optional.of(lot));

        // Alice example: 10,000 kg delivered and sold for 2,952,000 RWF.
        when(lotService.quantitiesOf(lot)).thenReturn(new LotQuantities(3L, new BigDecimal("10000.00"),
                null, new BigDecimal("10000.00"), 2_952_000L, 3L));
        when(lotShareRepository.findDeliveredByMember(3L)).thenReturn(List.of(
                new MemberDelivered(1L, "Alice", "M-0001", new BigDecimal("5000.00"), 25_000L),
                new MemberDelivered(2L, "Bosco", "M-0002", new BigDecimal("3000.00"), 15_000L),
                new MemberDelivered(3L, "Carine", "M-0003", new BigDecimal("2000.00"), 10_000L)));

        when(memberRepository.getReferenceById(anyLong())).thenAnswer(invocation -> {
            Member member = new Member();
            ReflectionTestUtils.setField(member, "id", invocation.getArgument(0));
            return member;
        });
        when(lotShareRepository.save(any(LotShare.class))).thenAnswer(invocation -> {
            LotShare share = invocation.getArgument(0);
            ReflectionTestUtils.setField(share, "id", 100L + share.getMember().getId());
            return share;
        });

        manager = new Manager();
        deliveryRepository = mock(DeliveryRepository.class);
        service = new SharingService(lotShareRepository, lotRepository, lotService, memberRepository, ledgerService,
                deliveryRepository);
    }

    @Test
    void previewSharesSaleMoneyByDeliveredKg() {
        SharePreviewResponse preview = service.preview(3L);

        assertEquals("A-2026-01", preview.lotCode());
        assertEquals("Big", preview.gradeName());
        assertEquals(2_952_000L, preview.saleAmountRwf());
        assertEquals(List.of(
                new SharePreviewResponse.MemberShare(1L, "Alice", "M-0001", new BigDecimal("5000.00"),
                        new BigDecimal("50.00"), 1_476_000L, 25_000L, 1_451_000L),
                new SharePreviewResponse.MemberShare(2L, "Bosco", "M-0002", new BigDecimal("3000.00"),
                        new BigDecimal("30.00"), 885_600L, 15_000L, 870_600L),
                new SharePreviewResponse.MemberShare(3L, "Carine", "M-0003", new BigDecimal("2000.00"),
                        new BigDecimal("20.00"), 590_400L, 10_000L, 580_400L)), preview.members());
        assertEquals(new SharePreviewResponse.Totals(2_952_000L, 50_000L, 2_902_000L), preview.totals());
    }

    @Test
    void previewDoesNotSaveAnything() {
        service.preview(3L);

        verify(lotShareRepository, never()).save(any());
        assertEquals(LotStatus.SOLD, lot.getStatus());
    }

    @Test
    void shareSavesOneRowPerMemberAndMarksLotShared() {
        service.share(3L, manager);

        ArgumentCaptor<LotShare> saved = ArgumentCaptor.forClass(LotShare.class);
        verify(lotShareRepository, times(3)).save(saved.capture());
        LotShare alice = saved.getAllValues().get(0);
        assertEquals(1L, alice.getMember().getId());
        assertEquals(new BigDecimal("5000.00"), alice.getMemberKg());
        assertEquals(new BigDecimal("10000.00"), alice.getLotKg());
        assertEquals(1_476_000L, alice.getShareAmount());
        assertEquals(25_000L, alice.getDeductionAmount());
        assertEquals(LotStatus.SHARED, lot.getStatus());
    }

    @Test
    void eachShareIsRecordedInLedger() {
        service.share(3L, manager);

        verify(ledgerService).record(LedgerEntryType.SHARE, "LOT_SHARE", 101L, new BigDecimal("5000.00"),
                1_476_000L, "Share of A-2026-01", manager);
        verify(ledgerService).record(LedgerEntryType.SHARE, "LOT_SHARE", 102L, new BigDecimal("3000.00"),
                885_600L, "Share of A-2026-01", manager);
        verify(ledgerService).record(LedgerEntryType.SHARE, "LOT_SHARE", 103L, new BigDecimal("2000.00"),
                590_400L, "Share of A-2026-01", manager);
    }

    @Test
    void lotThatIsNotSoldCannotBeShared() {
        lot.setStatus(LotStatus.CLOSED);

        BadRequestException e = assertThrows(BadRequestException.class, () -> service.share(3L, manager));

        assertEquals("Lot A-2026-01 is CLOSED. Only a sold lot can be shared.", e.getMessage());
        verify(lotShareRepository, never()).save(any());
    }

    @Test
    void openLotCannotBePreviewed() {
        lot.setStatus(LotStatus.OPEN);

        assertThrows(BadRequestException.class, () -> service.preview(3L));
    }

    @Test
    void lotWithPendingOrDisputedDeliveriesCannotBeShared() {
        Delivery disputed = delivery("RCT-9MNP", "80.00");
        disputed.dispute(DisputeReason.WRONG_KG, null, Instant.now());
        when(deliveryRepository.findByLotIdAndConfirmationStatusInAndReversedAtIsNullOrderById(3L,
                List.of(ConfirmationStatus.PENDING, ConfirmationStatus.DISPUTED)))
                .thenReturn(List.of(delivery("RCT-7K2Q", "120.50"), disputed));

        UnconfirmedDeliveriesException e = assertThrows(UnconfirmedDeliveriesException.class,
                () -> service.share(3L, manager));

        assertEquals("Lot A-2026-01 has deliveries that are not confirmed yet: RCT-7K2Q (PENDING), "
                + "RCT-9MNP (DISPUTED). Confirm, resolve, correct or reverse them before sharing.", e.getMessage());
        assertEquals(List.of(
                new UnconfirmedDelivery(42L, "RCT-7K2Q", 5L, "Uwimana Claudine", "M-0005", new BigDecimal("120.50"),
                        ConfirmationStatus.PENDING),
                new UnconfirmedDelivery(42L, "RCT-9MNP", 5L, "Uwimana Claudine", "M-0005", new BigDecimal("80.00"),
                        ConfirmationStatus.DISPUTED)), e.getDeliveries());
        verify(lotShareRepository, never()).save(any());
        verify(ledgerService, never()).record(any(), any(), any(), any(), any(), any(), any());
        assertEquals(LotStatus.SOLD, lot.getStatus());
    }

    @Test
    void previewIsAlsoRefusedWhileDeliveriesArePending() {
        when(deliveryRepository.findByLotIdAndConfirmationStatusInAndReversedAtIsNullOrderById(3L,
                List.of(ConfirmationStatus.PENDING, ConfirmationStatus.DISPUTED)))
                .thenReturn(List.of(delivery("RCT-7K2Q", "120.50")));

        UnconfirmedDeliveriesException e = assertThrows(UnconfirmedDeliveriesException.class,
                () -> service.preview(3L));

        assertEquals(1, e.getDeliveries().size());
    }

    private static Delivery delivery(String receiptCode, String quantityKg) {
        Member member = new Member();
        ReflectionTestUtils.setField(member, "id", 5L);
        member.setFullName("Uwimana Claudine");
        member.setMemberCode("M-0005");
        Delivery delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", 42L);
        delivery.setMember(member);
        delivery.setReceiptCode(receiptCode);
        delivery.setQuantityKg(new BigDecimal(quantityKg));
        return delivery;
    }

    @Test
    void lotCannotBeSharedTwice() {
        lot.setStatus(LotStatus.SHARED);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.share(3L, manager));

        assertEquals("Lot A-2026-01 has already been shared.", e.getMessage());
        verify(lotShareRepository, never()).save(any());
        verify(ledgerService, never()).record(any(), any(), any(), any(), any(), any(), any());
    }
}
