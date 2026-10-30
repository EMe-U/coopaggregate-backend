package com.coopaggregate.publicapi;

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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.coopaggregate.buyer.BuyerContactMethod;
import com.coopaggregate.buyer.BuyerRequest;
import com.coopaggregate.buyer.BuyerRequestService;
import com.coopaggregate.buyer.BuyerRequestStatus;
import com.coopaggregate.error.BadRequestException;

import jakarta.persistence.EntityNotFoundException;

@ExtendWith(OutputCaptureExtension.class)
class PublicRequestServiceTest {

    private BuyerRequestService requestService;
    private PublicRequestService service;

    @BeforeEach
    void setUp() {
        requestService = mock(BuyerRequestService.class);
        service = new PublicRequestService(requestService);
        BuyerRequest saved = new BuyerRequest();
        saved.setReferenceCode("BR-2026-7K2Q");
        when(requestService.createFromPublic(anyString(), anyString(), any(), anyString(), any(), any(), any(), any()))
                .thenReturn(saved);
    }

    @Test
    void validRequestIsSavedAndTheCodeReturned() {
        PublicRequestCreatedResponse response = service.submit(form("0788123456", null), "203.0.113.5");

        assertEquals(new PublicRequestCreatedResponse("BR-2026-7K2Q", BuyerRequestStatus.PENDING), response);
        verify(requestService).createFromPublic("Kalisa Traders", "0788123456", "kalisa@example.com", "A",
                new BigDecimal("250"), "Friday please", null, null);
    }

    @Test
    void theContactChoiceIsPassedOn() {
        service.submit(form("0788123456", null, BuyerContactMethod.WHATSAPP, "0788999999"), "203.0.113.5");

        verify(requestService).createFromPublic("Kalisa Traders", "0788123456", "kalisa@example.com", "A",
                new BigDecimal("250"), "Friday please", BuyerContactMethod.WHATSAPP, "0788999999");
    }

    @Test
    void theContactChoiceDoesNotChangeTheLimits() {
        // The limit is per phone number, whatever the WhatsApp number is.
        for (int i = 0; i < 3; i++) {
            service.submit(form("0788123456", null, BuyerContactMethod.WHATSAPP, "07889999" + (10 + i)),
                    "198.51.100." + i);
        }

        assertThrows(TooManyRequestsException.class,
                () -> service.submit(form("0788123456", null, BuyerContactMethod.WHATSAPP, "0788999990"),
                        "198.51.100.9"));
    }

    @Test
    void honeypotGetsANormalAnswerAndNothingIsSaved() {
        PublicRequestCreatedResponse response = service.submit(form("0788123456", "http://spam.example"),
                "203.0.113.5");

        assertTrue(response.code().matches("BR-\\d{4}-[2-9A-HJKMNP-Z]{4}"), response.code());
        assertEquals(BuyerRequestStatus.PENDING, response.status());
        verify(requestService, never()).createFromPublic(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void honeypotCallsDoNotUseUpTheLimit() {
        for (int i = 0; i < 30; i++) {
            service.submit(form("0788123456", "bot"), "203.0.113.5");
        }

        service.submit(form("0788123456", null), "203.0.113.5");

        verify(requestService, times(1)).createFromPublic(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void oneAddressCanSendTenRequestsAnHour() {
        for (int i = 0; i < 10; i++) {
            service.submit(form("07881234" + (10 + i), null), "203.0.113.5");
        }

        TooManyRequestsException e = assertThrows(TooManyRequestsException.class,
                () -> service.submit(form("0788999999", null), "203.0.113.5"));

        assertTrue(e.getRetryAfterSeconds() > 0);
        service.submit(form("0788999999", null), "203.0.113.6");
    }

    @Test
    void onePhoneNumberCanSendThreeRequestsADayFromAnyAddress() {
        service.submit(form("0788123456", null), "203.0.113.1");
        service.submit(form("+250788123456", null), "203.0.113.2");
        service.submit(form("250788123456", null), "203.0.113.3");

        assertThrows(TooManyRequestsException.class, () -> service.submit(form("0788123456", null), "203.0.113.4"));
        verify(requestService, times(3)).createFromPublic(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void lookupsAreLimitedPerAddress() {
        when(requestService.findForPublic(anyString(), anyString())).thenThrow(
                new EntityNotFoundException("Request not found."));

        for (int i = 0; i < 30; i++) {
            assertThrows(EntityNotFoundException.class, () -> service.status("BR-2026-AAAA", "0788123456", "198.51.100.7"));
        }

        assertThrows(TooManyRequestsException.class, () -> service.status("BR-2026-AAAA", "0788123456", "198.51.100.7"));
        assertThrows(EntityNotFoundException.class, () -> service.status("BR-2026-AAAA", "0788123456", "198.51.100.8"));
    }

    @Test
    void statusShowsOnlyTheStatusThePublicMessageAndHowTheyWillBeContacted() {
        BuyerRequest request = new BuyerRequest();
        request.setReferenceCode("BR-2026-7K2Q");
        request.setStatus(BuyerRequestStatus.ACCEPTED);
        request.setPublicMessage("We will call you on Thursday.");
        request.setManagerNote("Private: slow payer");
        request.setRequesterPhone("+250788123456");
        when(requestService.findForPublic("BR-2026-7K2Q", "0788123456")).thenReturn(request);

        PublicRequestStatusResponse response = service.status("BR-2026-7K2Q", "0788123456", "198.51.100.7");

        // A request made before contact methods existed is CALL, on the phone it was sent with.
        assertEquals(new PublicRequestStatusResponse("BR-2026-7K2Q", BuyerRequestStatus.ACCEPTED,
                "We will call you on Thursday.", BuyerContactMethod.CALL, "+250788123456"), response);
    }

    @Test
    void statusOfAWhatsappRequestShowsTheWhatsappNumber() {
        BuyerRequest request = new BuyerRequest();
        request.setReferenceCode("BR-2026-7K2Q");
        request.setRequesterPhone("+250788123456");
        request.setContactMethod(BuyerContactMethod.WHATSAPP);
        request.setWhatsappNumber("+250788999999");
        when(requestService.findForPublic("BR-2026-7K2Q", "0788123456")).thenReturn(request);

        PublicRequestStatusResponse response = service.status("BR-2026-7K2Q", "0788123456", "198.51.100.7");

        assertEquals(BuyerContactMethod.WHATSAPP, response.contactMethod());
        assertEquals("+250788999999", response.contactNumber());
    }

    @Test
    void statusOfAWhatsappRequestWithoutAnotherNumberShowsTheMainPhone() {
        BuyerRequest request = new BuyerRequest();
        request.setReferenceCode("BR-2026-7K2Q");
        request.setRequesterPhone("+250788123456");
        request.setContactMethod(BuyerContactMethod.WHATSAPP);
        request.setWhatsappNumber("+250788123456");
        when(requestService.findForPublic("BR-2026-7K2Q", "0788123456")).thenReturn(request);

        assertEquals("+250788123456", service.status("BR-2026-7K2Q", "0788123456", "198.51.100.7").contactNumber());
    }

    @Test
    void noPersonalDataReachesTheLogs(CapturedOutput output) {
        service.submit(form("0788123456", null, BuyerContactMethod.WHATSAPP, "0788999999"), "203.0.113.5");
        service.submit(form("0788123456", "bot"), "203.0.113.5");
        when(requestService.createFromPublic(anyString(), anyString(), any(), anyString(), any(), any(), any(), any()))
                .thenThrow(new BadRequestException("The smallest order is 100 kg."));
        assertThrows(BadRequestException.class, () -> service.submit(form("0788123457", null), "203.0.113.5"));
        for (int i = 0; i < 12; i++) {
            try {
                service.submit(form("07881234" + (10 + i), null), "203.0.113.5");
            } catch (RuntimeException expected) {
                // The limit is reached on purpose.
            }
        }

        String logs = output.getAll();
        assertFalse(logs.contains("Kalisa"));
        assertFalse(logs.contains("0788123456"));
        assertFalse(logs.contains("788123456"));
        assertFalse(logs.contains("kalisa@example.com"));
        assertFalse(logs.contains("0788999999"));
        assertFalse(logs.contains("788999999"));
        assertFalse(logs.contains("203.0.113.5"));
    }

    @Test
    void formPrintsWithoutPersonalData() {
        String text = form("0788123456", null).toString();

        assertFalse(text.contains("Kalisa"));
        assertFalse(text.contains("0788123456"));
        assertFalse(text.contains("kalisa@example.com"));
        assertTrue(text.contains("gradeCode=A"));
    }

    @Test
    void formPrintsWithoutTheWhatsappNumber() {
        String text = form("0788123456", null, BuyerContactMethod.WHATSAPP, "0788999999").toString();

        assertFalse(text.contains("0788999999"));
        assertFalse(text.contains("788999999"));
    }

    private static PublicRequestForm form(String phone, String website) {
        return form(phone, website, null, null);
    }

    private static PublicRequestForm form(String phone, String website, BuyerContactMethod contactMethod,
                                          String whatsappNumber) {
        return new PublicRequestForm("Kalisa Traders", phone, "kalisa@example.com", "A", new BigDecimal("250"),
                "Friday please", contactMethod, whatsappNumber, website);
    }
}
