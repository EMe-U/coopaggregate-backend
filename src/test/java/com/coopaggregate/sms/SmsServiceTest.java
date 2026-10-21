package com.coopaggregate.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.delivery.ConfirmationCodes;
import com.coopaggregate.delivery.ConfirmationMethod;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.ledger.LedgerEntry;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.member.Member;
import com.coopaggregate.setting.Setting;
import com.coopaggregate.setting.SettingRepository;

@ExtendWith(OutputCaptureExtension.class)
class SmsServiceTest {

    private static final Pattern CODE = Pattern.compile("code (\\d{6})\\.");
    private static final String MASKED = "CoopAggregate: RCT-7K2Q, 300 kg Big, 19/10. If correct, give the manager "
            + "code ******. If wrong, don't give the code, reply 2 RCT-7K2Q.";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private SmsLogRepository smsLogRepository;
    private SmsSender smsSender;
    private Setting setting;
    private Delivery delivery;
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
        delivery = delivery();
        when(deliveryRepository.findById(42L)).thenReturn(Optional.of(delivery));
        when(deliveryRepository.findForUpdateById(42L)).thenReturn(Optional.of(delivery));
        when(smsLogRepository.save(any(SmsLog.class))).thenAnswer(invocation -> {
            SmsLog sms = invocation.getArgument(0);
            ReflectionTestUtils.setField(sms, "id", 7L);
            return sms;
        });

        service = new SmsService(smsLogRepository, deliveryRepository, settingRepository, smsSender,
                new ConfirmationCodes(encoder));
    }

    // --- Queueing ---

    @Test
    void codeMessageIsQueuedWithTheCodeMasked() {
        Optional<Long> id = service.queueCodeMessage(42L, 0);

        assertEquals(Optional.of(7L), id);
        ArgumentCaptor<SmsLog> sms = ArgumentCaptor.forClass(SmsLog.class);
        verify(smsLogRepository).save(sms.capture());
        assertEquals("+250788123456", sms.getValue().getPhone());
        assertEquals(SmsStatus.QUEUED, sms.getValue().getStatus());
        assertEquals(0, sms.getValue().getSendNumber());
        assertEquals(MASKED, sms.getValue().getMessage());
        assertNull(delivery.getConfirmationCodeHash());
    }

    @Test
    void noCodeIsQueuedWhenSmsAreTurnedOff() {
        setting.setSmsEnabled(false);

        assertTrue(service.queueCodeMessage(42L, 0).isEmpty());
        verify(smsLogRepository, never()).save(any());
    }

    @Test
    void theSameMessageIsQueuedOnlyOnce() {
        when(smsLogRepository.existsByDeliveryIdAndSendNumber(42L, 0)).thenReturn(true);

        assertTrue(service.queueCodeMessage(42L, 0).isEmpty());
        verify(smsLogRepository, never()).save(any());
    }

    // --- Sending a code ---

    @Test
    void sentMessageHasARealCodeThatOnlyTheHashRemembers() {
        SmsLog sms = queuedCodeMessage(0);
        Instant before = Instant.now();

        service.trySend(7L);

        String code = sentCode();
        assertEquals(SmsStatus.SENT, sms.getStatus());
        assertEquals(MASKED, sms.getMessage());
        assertFalse(sms.getMessage().contains(code));
        assertNotEquals(code, delivery.getConfirmationCodeHash());
        assertTrue(encoder.matches(code, delivery.getConfirmationCodeHash()));
        Duration validity = Duration.between(before, delivery.getConfirmationCodeExpiresAt());
        assertTrue(validity.compareTo(Duration.ofMinutes(10)) <= 0 && validity.compareTo(Duration.ofMinutes(9)) > 0);
    }

    @Test
    void failedSendKillsTheCodeAndNeverLogsIt(CapturedOutput output) {
        SmsLog sms = queuedCodeMessage(0);
        AtomicReference<String> sentText = new AtomicReference<>();
        doAnswer(invocation -> {
            sentText.set(invocation.getArgument(1));
            throw new SmsSendException("Provider rejected: " + invocation.getArgument(1));
        }).when(smsSender).send(anyString(), anyString());

        service.trySend(7L);

        Matcher matcher = CODE.matcher(sentText.get());
        assertTrue(matcher.find());
        String code = matcher.group(1);
        assertEquals(SmsStatus.FAILED, sms.getStatus());
        assertNull(delivery.getConfirmationCodeHash());
        assertFalse(sms.getLastError().contains(code));
        assertFalse(sms.getMessage().contains(code));
        assertFalse(output.getAll().contains(code));
    }

    @Test
    void retryGetsANewCodeAndTheOldOneStopsWorking() {
        queuedCodeMessage(0);
        service.trySend(7L);
        String first = sentCode();
        ReflectionTestUtils.setField(smsLogRepository.findById(7L).orElseThrow(), "status", SmsStatus.FAILED);

        service.trySend(7L);

        ArgumentCaptor<String> texts = ArgumentCaptor.forClass(String.class);
        verify(smsSender, times(2)).send(anyString(), texts.capture());
        Matcher matcher = CODE.matcher(texts.getAllValues().get(1));
        assertTrue(matcher.find());
        String second = matcher.group(1);
        assertTrue(encoder.matches(second, delivery.getConfirmationCodeHash()));
        // Two random codes are the same once in a million; only then may the first one still match.
        if (!first.equals(second)) {
            assertFalse(encoder.matches(first, delivery.getConfirmationCodeHash()));
        }
    }

    @Test
    void codeIsNotSentForAConfirmedDelivery() {
        SmsLog sms = queuedCodeMessage(0);
        delivery.confirm(ConfirmationMethod.SMS, Instant.now());

        service.trySend(7L);

        verify(smsSender, never()).send(anyString(), anyString());
        assertEquals(SmsStatus.CANCELLED, sms.getStatus());
    }

    @Test
    void codeIsNotSentForAReversedDelivery() {
        SmsLog sms = queuedCodeMessage(0);
        LedgerEntry reversal = new LedgerEntry();
        reversal.setEntryDate(Instant.now());
        delivery.markReversed(reversal);

        service.trySend(7L);

        verify(smsSender, never()).send(anyString(), anyString());
        assertEquals(SmsStatus.CANCELLED, sms.getStatus());
    }

    @Test
    void olderCodeIsNotSentOnceANewerOneWasRequested() {
        SmsLog sms = queuedCodeMessage(0);
        when(smsLogRepository.existsByDeliveryIdAndSendNumberGreaterThan(42L, 0)).thenReturn(true);

        service.trySend(7L);

        verify(smsSender, never()).send(anyString(), anyString());
        assertEquals(SmsStatus.CANCELLED, sms.getStatus());
        assertEquals("A newer code was requested.", sms.getLastError());
    }

    @Test
    void cancelledMessageIsNeverSent() {
        SmsLog sms = queuedCodeMessage(0);
        sms.cancel("A newer code was requested.");

        service.trySend(7L);

        verify(smsSender, never()).send(anyString(), anyString());
    }

    // --- Plain messages and retries ---

    @Test
    void messageWithoutCodeIsSentAsSaved() {
        SmsLog sms = plainMessage();

        service.trySend(7L);

        verify(smsSender).send("+250788123456", "Hello");
        assertEquals(SmsStatus.SENT, sms.getStatus());
        assertEquals("test", sms.getProvider());
        assertEquals(1, sms.getAttempts());
        assertTrue(sms.getSentAt() != null);
    }

    @Test
    void providerFailureIsRecordedAndNotThrown() {
        SmsLog sms = plainMessage();
        doThrow(new SmsSendException("Africa's Talking did not accept the message: InsufficientBalance"))
                .when(smsSender).send(anyString(), anyString());

        service.trySend(7L);

        assertEquals(SmsStatus.FAILED, sms.getStatus());
        assertEquals(1, sms.getAttempts());
        assertEquals("Africa's Talking did not accept the message: InsufficientBalance", sms.getLastError());
    }

    @Test
    void anUnexpectedProviderErrorIsAlsoCaught() {
        SmsLog sms = plainMessage();
        doThrow(new IllegalStateException("network down")).when(smsSender).send(anyString(), anyString());

        service.trySend(7L);

        assertEquals(SmsStatus.FAILED, sms.getStatus());
    }

    @Test
    void sentMessageIsNotSentAgain() {
        SmsLog sms = plainMessage();
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

    private String sentCode() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(smsSender).send(eq("+250788123456"), text.capture());
        Matcher matcher = CODE.matcher(text.getValue());
        assertTrue(matcher.find(), text.getValue());
        return matcher.group(1);
    }

    private SmsLog queuedCodeMessage(int sendNumber) {
        SmsLog sms = new SmsLog();
        ReflectionTestUtils.setField(sms, "id", 7L);
        sms.setDelivery(delivery);
        sms.setSendNumber(sendNumber);
        sms.setPhone("+250788123456");
        sms.setMessage(MASKED);
        when(smsLogRepository.findById(7L)).thenReturn(Optional.of(sms));
        return sms;
    }

    private SmsLog plainMessage() {
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
