package com.coopaggregate.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.delivery.ConfirmationMethod;
import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.delivery.DisputeReason;
import com.coopaggregate.ledger.LedgerEntry;
import com.coopaggregate.member.Member;

class InboundSmsServiceTest {

    private static final String OWNER_PHONE = "+250788123456";

    private Delivery delivery;
    private InboundSmsService service;

    @BeforeEach
    void setUp() {
        DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
        Member member = new Member();
        member.setPhone(OWNER_PHONE);
        delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", 42L);
        delivery.setReceiptCode("RCT-7K2Q");
        delivery.setMember(member);
        when(deliveryRepository.findByReceiptCode("RCT-7K2Q")).thenReturn(Optional.of(delivery));
        when(deliveryRepository.findByReceiptCode("RCT-ZZZZ")).thenReturn(Optional.empty());
        service = new InboundSmsService(deliveryRepository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1 RCT-7K2Q", "1 rct-7k2q", "  1   RCT-7K2Q  "})
    void replyOneConfirmsThePendingDelivery(String text) {
        assertEquals(InboundSmsService.Result.CONFIRMED, service.handle(OWNER_PHONE, text));

        assertEquals(ConfirmationStatus.CONFIRMED, delivery.getConfirmationStatus());
        assertEquals(ConfirmationMethod.SMS, delivery.getConfirmationMethod());
    }

    @Test
    void replyTwoDisputesWithTheNote() {
        assertEquals(InboundSmsService.Result.DISPUTED, service.handle(OWNER_PHONE, "2 RCT-7K2Q it was 250 kg"));

        assertEquals(ConfirmationStatus.DISPUTED, delivery.getConfirmationStatus());
        assertEquals(DisputeReason.OTHER, delivery.getDisputeReason());
        assertEquals("it was 250 kg", delivery.getDisputeNote());
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
