package com.coopaggregate.buyer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.setting.Setting;
import com.coopaggregate.setting.SettingRepository;

import jakarta.persistence.EntityNotFoundException;

class BuyerRequestServiceTest {

    private BuyerRequestRepository requestRepository;
    private BuyerRepository buyerRepository;
    private AuditLogRepository auditLogRepository;
    private GradeRepository gradeRepository;
    private Setting setting;
    private Grade big;
    private BuyerRequestService service;

    @BeforeEach
    void setUp() {
        requestRepository = mock(BuyerRequestRepository.class);
        gradeRepository = mock(GradeRepository.class);
        SettingRepository settingRepository = mock(SettingRepository.class);
        setting = new Setting();
        when(settingRepository.findById(1L)).thenReturn(Optional.of(setting));
        big = new Grade();
        big.setCode("A");
        big.setName("Big");
        when(gradeRepository.findByCodeAndActiveTrue("A")).thenReturn(Optional.of(big));
        when(requestRepository.save(any(BuyerRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        buyerRepository = mock(BuyerRepository.class);
        auditLogRepository = mock(AuditLogRepository.class);
        service = new BuyerRequestService(requestRepository, gradeRepository, settingRepository, buyerRepository,
                auditLogRepository);
    }

    @Test
    void requestIsSavedAsPendingWithTheBuyersContactDetails() {
        BuyerRequest request = create("0788123456", new BigDecimal("250.50"));

        assertEquals(BuyerRequestStatus.PENDING, request.getStatus());
        assertEquals("Kalisa Traders", request.getRequesterName());
        assertEquals("+250788123456", request.getRequesterPhone());
        assertEquals("kalisa@example.com", request.getRequesterEmail());
        assertEquals(big, request.getGrade());
        assertEquals(new BigDecimal("250.50"), request.getQuantityKg());
        assertEquals("Pick up on Friday", request.getMessage());
        assertNull(request.getBuyer());
    }

    // --- How the buyer wants to be contacted ---

    @Test
    void withoutAChoiceTheCooperativeCalls() {
        BuyerRequest request = createWithContact(null, null);

        assertEquals(BuyerContactMethod.CALL, request.getContactMethod());
        assertNull(request.getWhatsappNumber());
    }

    @Test
    void whatsappWithAnotherNumberStoresThatNumberInTheStoredFormat() {
        BuyerRequest request = createWithContact(BuyerContactMethod.WHATSAPP, "0788999999");

        assertEquals(BuyerContactMethod.WHATSAPP, request.getContactMethod());
        assertEquals("+250788999999", request.getWhatsappNumber());
        assertEquals("+250788123456", request.getRequesterPhone());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void whatsappWithoutANumberUsesTheMainPhone(String whatsappNumber) {
        BuyerRequest request = createWithContact(BuyerContactMethod.WHATSAPP, whatsappNumber);

        assertEquals("+250788123456", request.getWhatsappNumber());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0788999999", "250788999999", "+250788999999"})
    void whatsappNumberIsAcceptedInEveryAllowedFormat(String whatsappNumber) {
        assertEquals("+250788999999", createWithContact(BuyerContactMethod.WHATSAPP, whatsappNumber).getWhatsappNumber());
    }

    @Test
    void aWhatsappNumberIsIgnoredForACall() {
        BuyerRequest request = createWithContact(BuyerContactMethod.CALL, "0788999999");

        assertEquals(BuyerContactMethod.CALL, request.getContactMethod());
        assertNull(request.getWhatsappNumber(), "a call request must not keep a WhatsApp number");
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "0711111111", "+250188999999", "abc"})
    void aWhatsappNumberThatIsNotRwandanIsRefusedAndNothingIsSaved(String whatsappNumber) {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> createWithContact(BuyerContactMethod.WHATSAPP, whatsappNumber));

        assertEquals("whatsappNumber must be a Rwandan mobile number: 07XXXXXXXX, 2507XXXXXXXX or +2507XXXXXXXX",
                e.getMessage());
        verify(requestRepository, never()).save(any());
    }

    @Test
    void codeLooksLikeBrYearAndFourClearCharacters() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String code = create("0788123456", new BigDecimal("100")).getReferenceCode();
            assertTrue(code.matches("BR-" + KigaliTime.today().getYear() + "-[2-9A-HJKMNP-Z]{4}"), code);
            codes.add(code);
        }
        assertTrue(codes.size() > 150, "codes should be random, got " + codes.size() + " different ones");
    }

    @Test
    void codeThatIsAlreadyTakenIsNotUsed() {
        when(requestRepository.existsByReferenceCode(anyString())).thenReturn(true, true, false);

        BuyerRequest request = create("0788123456", new BigDecimal("100"));

        verify(requestRepository, org.mockito.Mockito.times(3)).existsByReferenceCode(anyString());
        assertTrue(request.getReferenceCode().startsWith("BR-"));
    }

    @Test
    void orderBelowTheMinimumIsRefused() {
        setting.setMinOrderKg(new BigDecimal("100"));

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> create("0788123456", new BigDecimal("99.99")));

        assertEquals("The smallest order is 100 kg.", e.getMessage());
        verify(requestRepository, never()).save(any());
    }

    @Test
    void orderOfExactlyTheMinimumIsAccepted() {
        setting.setMinOrderKg(new BigDecimal("100.00"));

        assertEquals(new BigDecimal("100"), create("0788123456", new BigDecimal("100")).getQuantityKg());
    }

    @Test
    void unknownOrInactiveGradeIsRefused() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.createFromPublic(
                "Kalisa Traders", "0788123456", null, "Z", new BigDecimal("100"), null, null, null));

        assertEquals("Unknown grade. Choose one of the grades shown.", e.getMessage());
    }

    @Test
    void gradeCodeIsNotCaseSensitive() {
        BuyerRequest request = service.createFromPublic("Kalisa Traders", "0788123456", "  ", " a ",
                new BigDecimal("100"), " ", null, null);

        assertEquals(big, request.getGrade());
        assertNull(request.getRequesterEmail());
        assertNull(request.getMessage());
    }

    @Test
    void lookupWorksWithTheSamePhoneInAnyAllowedFormat() {
        BuyerRequest saved = create("0788123456", new BigDecimal("100"));
        when(requestRepository.findByReferenceCode("BR-2026-7K2Q")).thenReturn(Optional.of(saved));

        assertSame(saved, service.findForPublic("BR-2026-7K2Q", "+250788123456"));
        assertSame(saved, service.findForPublic("br-2026-7k2q", "0788123456"));
        assertSame(saved, service.findForPublic("BR-2026-7K2Q", "250788123456"));
    }

    @Test
    void wrongPhoneGivesTheSame404AsAnUnknownCode() {
        BuyerRequest saved = create("0788123456", new BigDecimal("100"));
        when(requestRepository.findByReferenceCode("BR-2026-7K2Q")).thenReturn(Optional.of(saved));
        when(requestRepository.findByReferenceCode("BR-2026-ZZZZ")).thenReturn(Optional.empty());

        EntityNotFoundException wrongPhone = assertThrows(EntityNotFoundException.class,
                () -> service.findForPublic("BR-2026-7K2Q", "0722000111"));
        EntityNotFoundException unknownCode = assertThrows(EntityNotFoundException.class,
                () -> service.findForPublic("BR-2026-ZZZZ", "0788123456"));
        EntityNotFoundException noPhone = assertThrows(EntityNotFoundException.class,
                () -> service.findForPublic("BR-2026-7K2Q", null));
        EntityNotFoundException badPhone = assertThrows(EntityNotFoundException.class,
                () -> service.findForPublic("BR-2026-7K2Q", "not a phone"));

        assertEquals("Request not found.", wrongPhone.getMessage());
        assertEquals(wrongPhone.getMessage(), unknownCode.getMessage());
        assertEquals(wrongPhone.getMessage(), noPhone.getMessage());
        assertEquals(wrongPhone.getMessage(), badPhone.getMessage());
    }

    @Test
    void requestWithoutAPhoneIsNeverFoundThroughThePublicLookup() {
        BuyerRequest withoutPhone = new BuyerRequest();
        withoutPhone.setReferenceCode("BR-2026-7K2Q");
        when(requestRepository.findByReferenceCode("BR-2026-7K2Q")).thenReturn(Optional.of(withoutPhone));

        assertThrows(EntityNotFoundException.class, () -> service.findForPublic("BR-2026-7K2Q", "0788123456"));
    }

    private BuyerRequest create(String phone, BigDecimal quantityKg) {
        return service.createFromPublic(" Kalisa Traders ", phone, "kalisa@example.com", "A", quantityKg,
                "Pick up on Friday", null, null);
    }

    private BuyerRequest createWithContact(BuyerContactMethod contactMethod, String whatsappNumber) {
        return service.createFromPublic("Kalisa Traders", "0788123456", "kalisa@example.com", "A",
                new BigDecimal("250"), "Pick up on Friday", contactMethod, whatsappNumber);
    }
}
