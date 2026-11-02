package com.coopaggregate.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.delivery.ConfirmationMethod;
import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DeliveryConfirmedEvent;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.delivery.DisputeReason;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.lot.LotFiller;
import com.coopaggregate.ledger.LedgerEntry;
import com.coopaggregate.member.Member;

class InboundSmsServiceTest {

    private static final String OWNER_PHONE = "+250788123456";

    private Delivery delivery;
    private DeliveryRepository deliveryRepository;
    private ApplicationEventPublisher eventPublisher;
    private LotFiller lotFiller;
    private GradeRepository gradeRepository;
    private InboundSmsService service;

    @BeforeEach
    void setUp() {
        deliveryRepository = mock(DeliveryRepository.class);
        Member member = new Member();
        member.setPhone(OWNER_PHONE);
        delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", 42L);
        delivery.setReceiptCode("RCT-7K2Q");
        delivery.setMember(member);
        when(deliveryRepository.findByReceiptCode("RCT-7K2Q")).thenReturn(Optional.of(delivery));
        when(deliveryRepository.findByReceiptCode("RCT-ZZZZ")).thenReturn(Optional.empty());
        eventPublisher = mock(ApplicationEventPublisher.class);
        lotFiller = mock(LotFiller.class);
        when(deliveryRepository.findIdByReceiptCode("RCT-7K2Q")).thenReturn(Optional.of(42L));
        when(deliveryRepository.findGradeIdById(42L)).thenReturn(Optional.of(1L));
        when(deliveryRepository.findForUpdateById(42L)).thenReturn(Optional.of(delivery));
        gradeRepository = mock(GradeRepository.class);
        service = new InboundSmsService(deliveryRepository, gradeRepository, lotFiller, eventPublisher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1 RCT-7K2Q", "1 rct-7k2q", "  1   RCT-7K2Q  "})
    void replyOneConfirmsThePendingDelivery(String text) {
        assertEquals(InboundSmsService.Result.CONFIRMED, service.handle(OWNER_PHONE, text));

        assertEquals(ConfirmationStatus.CONFIRMED, delivery.getConfirmationStatus());
        assertEquals(ConfirmationMethod.SMS, delivery.getConfirmationMethod());
        verify(eventPublisher).publishEvent(new DeliveryConfirmedEvent(42L));
        InOrder order = inOrder(gradeRepository, lotFiller, deliveryRepository);
        order.verify(gradeRepository).findForUpdateById(1L);
        order.verify(lotFiller).lockFillingLot(1L);
        order.verify(deliveryRepository).findForUpdateById(42L);
        order.verify(lotFiller).place(delivery);
    }

    @Test
    void replyTwoDisputesWithTheNote() {
        assertEquals(InboundSmsService.Result.DISPUTED, service.handle(OWNER_PHONE, "2 RCT-7K2Q it was 250 kg"));

        assertEquals(ConfirmationStatus.DISPUTED, delivery.getConfirmationStatus());
        assertEquals(DisputeReason.OTHER, delivery.getDisputeReason());
        assertEquals("it was 250 kg", delivery.getDisputeNote());
        verify(eventPublisher, never()).publishEvent(any());
        verify(lotFiller, never()).place(any());
    }

    @Test
    void replyTwoWithoutANoteDisputes() {
        service.handle(OWNER_PHONE, "2 RCT-7K2Q");

        assertEquals(ConfirmationStatus.DISPUTED, delivery.getConfirmationStatus());
        assertEquals(null, delivery.getDisputeNote());
    }

    @Test
    void replyTwoWorksOnAConfirmedDelivery() {
        delivery.confirm(ConfirmationMethod.PIN, Instant.now());

        assertEquals(InboundSmsService.Result.DISPUTED, service.handle(OWNER_PHONE, "2 RCT-7K2Q this was not me"));
        assertEquals(ConfirmationStatus.DISPUTED, delivery.getConfirmationStatus());
    }

    @Test
    void replyTwoStillDisputesADeliveryTheManagerReviewed() {
        delivery.confirmByManagerReview("Weighed again", Instant.now());

        assertEquals(InboundSmsService.Result.DISPUTED, service.handle(OWNER_PHONE, "2 RCT-7K2Q not 300 kg"));
        assertEquals(ConfirmationStatus.DISPUTED, delivery.getConfirmationStatus());
        assertEquals("not 300 kg", delivery.getDisputeNote());
    }

    @Test
    void replyFromAnotherPhoneIsIgnored() {
        assertEquals(InboundSmsService.Result.IGNORED, service.handle("+250722000111", "2 RCT-7K2Q not mine"));

        assertEquals(ConfirmationStatus.PENDING, delivery.getConfirmationStatus());
    }

    @Test
    void ownerWritingTheLocalNumberFormatIsRecognised() {
        assertEquals(InboundSmsService.Result.CONFIRMED, service.handle("0788123456", "1 RCT-7K2Q"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hello", "3 RCT-7K2Q", "1RCT-7K2Q", "1 RCT-7K2", "yes", ""})
    void anythingElseIsIgnored(String text) {
        assertEquals(InboundSmsService.Result.IGNORED, service.handle(OWNER_PHONE, text));

        assertEquals(ConfirmationStatus.PENDING, delivery.getConfirmationStatus());
    }

    @Test
    void unknownCodeIsIgnored() {
        assertEquals(InboundSmsService.Result.IGNORED, service.handle(OWNER_PHONE, "1 RCT-ZZZZ"));
    }

    @Test
    void replyOneOnAnAlreadyConfirmedDeliveryChangesNothing() {
        delivery.confirm(ConfirmationMethod.PIN, Instant.now());

        assertEquals(InboundSmsService.Result.IGNORED, service.handle(OWNER_PHONE, "1 RCT-7K2Q"));
        assertEquals(ConfirmationMethod.PIN, delivery.getConfirmationMethod());
    }

    @Test
    void replyAboutAReversedDeliveryIsIgnored() {
        LedgerEntry reversal = new LedgerEntry();
        reversal.setEntryDate(Instant.now());
        delivery.markReversed(reversal);

        assertEquals(InboundSmsService.Result.IGNORED, service.handle(OWNER_PHONE, "2 RCT-7K2Q"));
    }
}
