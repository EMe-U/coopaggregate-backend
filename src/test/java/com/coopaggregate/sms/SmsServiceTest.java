package com.coopaggregate.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
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
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.member.Member;
import com.coopaggregate.setting.Setting;
import com.coopaggregate.setting.SettingRepository;

class SmsServiceTest {

    private SmsLogRepository smsLogRepository;
    private SmsSender smsSender;
    private Setting setting;
    private SmsService service;

    @BeforeEach
    void setUp() {
        smsLogRepository = mock(SmsLogRepository.class);
        DeliveryRepository deliveryRepository = mock(DeliveryRepository.class);
        SettingRepository settingRepository = mock(SettingRepository.class);
        smsSender = mock(SmsSender.class);
        when(smsSender.name()).thenReturn("test");

        setting = new Setting();
        when(settingRepository.findById(1L)).thenReturn(Optional.of(setting));
        when(deliveryRepository.findById(42L)).thenReturn(Optional.of(delivery()));
        when(smsLogRepository.save(any(SmsLog.class))).thenAnswer(invocation -> {
            SmsLog sms = invocation.getArgument(0);
            ReflectionTestUtils.setField(sms, "id", 7L);
            return sms;
        });

        service = new SmsService(smsLogRepository, deliveryRepository, settingRepository, smsSender);
    }

    @Test
    void deliveryMessageIsQueuedInTheMembersLanguage() {
        Optional<Long> id = service.queueDeliveryMessage(42L);

        assertEquals(Optional.of(7L), id);
        ArgumentCaptor<SmsLog> sms = ArgumentCaptor.forClass(SmsLog.class);
        verify(smsLogRepository).save(sms.capture());
        assertEquals("+250788123456", sms.getValue().getPhone());
        assertEquals(SmsStatus.QUEUED, sms.getValue().getStatus());
        assertEquals("CoopAggregate: RCT-7K2Q, 300 kg Big, 19/10. Reply 1 RCT-7K2Q if correct, 2 RCT-7K2Q if wrong.",
                sms.getValue().getMessage());
    }

    @Test
    void nothingIsQueuedWhenSmsAreTurnedOff() {
        setting.setSmsEnabled(false);

        assertTrue(service.queueDeliveryMessage(42L).isEmpty());
        verify(smsLogRepository, never()).save(any());
    }

    @Test
    void aDeliveryGetsOnlyOneMessage() {
        when(smsLogRepository.existsByDeliveryId(42L)).thenReturn(true);

        assertTrue(service.queueDeliveryMessage(42L).isEmpty());
        verify(smsLogRepository, never()).save(any());
    }

    @Test
    void sentMessageIsMarkedSent() {
        SmsLog sms = queued();

        service.trySend(7L);

        verify(smsSender).send("+250788123456", "Hello");
        assertEquals(SmsStatus.SENT, sms.getStatus());
        assertEquals("test", sms.getProvider());
        assertEquals(1, sms.getAttempts());
        assertTrue(sms.getSentAt() != null);
    }

    @Test
    void providerFailureIsRecordedAndNotThrown() {
        SmsLog sms = queued();
        doThrow(new SmsSendException("Africa's Talking did not accept the message: InsufficientBalance"))
                .when(smsSender).send(anyString(), anyString());

        service.trySend(7L);

        assertEquals(SmsStatus.FAILED, sms.getStatus());
        assertEquals(1, sms.getAttempts());
        assertEquals("Africa's Talking did not accept the message: InsufficientBalance", sms.getLastError());
    }

    @Test
    void anUnexpectedProviderErrorIsAlsoCaught() {
        SmsLog sms = queued();
        doThrow(new IllegalStateException("network down")).when(smsSender).send(anyString(), anyString());

        service.trySend(7L);

        assertEquals(SmsStatus.FAILED, sms.getStatus());
    }

    @Test
    void sentMessageIsNotSentAgain() {
        SmsLog sms = queued();
        sms.markSent("test", Instant.now());

        service.trySend(7L);

        verify(smsSender, never()).send(anyString(), anyString());
    }

    @Test
    void failedAndStuckMessagesAreRetried() {
        SmsLog failed = new SmsLog();
        ReflectionTestUtils.setField(failed, "id", 3L);
        SmsLog stuck = new SmsLog();
        ReflectionTestUtils.setField(stuck, "id", 4L);
        when(smsLogRepository.findTop50ByStatusAndAttemptsLessThanOrderByIdAsc(SmsStatus.FAILED,
                SmsService.MAX_ATTEMPTS)).thenReturn(List.of(failed));
        when(smsLogRepository.findTop50ByStatusAndCreatedAtBeforeOrderByIdAsc(any(), any()))
                .thenReturn(List.of(stuck));

        assertEquals(List.of(3L, 4L), service.findRetryableIds());
    }

    private SmsLog queued() {
        SmsLog sms = new SmsLog();
        ReflectionTestUtils.setField(sms, "id", 7L);
        sms.setPhone("+250788123456");
        sms.setMessage("Hello");
        when(smsLogRepository.findById(7L)).thenReturn(Optional.of(sms));
        return sms;
    }

    private static Delivery delivery() {
        Member member = new Member();
        member.setPhone("+250788123456");
        member.setPreferredLanguage("en");
        Grade grade = new Grade();
        grade.setName("Big");
        Lot lot = new Lot();
        lot.setGrade(grade);
        Delivery delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", 42L);
        delivery.setMember(member);
        delivery.setLot(lot);
        delivery.setReceiptCode("RCT-7K2Q");
        delivery.setQuantityKg(new BigDecimal("300.00"));
        delivery.setDeliveredAt(Instant.parse("2026-10-19T07:30:00Z"));
        return delivery;
    }
}
