package com.coopaggregate.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.StockResponse;
import com.coopaggregate.setting.Setting;
import com.coopaggregate.setting.SettingRepository;

class PublicServiceTest {

    private GradeRepository gradeRepository;
    private SettingRepository settingRepository;
    private LotService lotService;
    private Setting setting;
    private PublicService service;

    @BeforeEach
    void setUp() {
        gradeRepository = mock(GradeRepository.class);
        settingRepository = mock(SettingRepository.class);
        lotService = mock(LotService.class);
        setting = new Setting();
        when(settingRepository.findById(1L)).thenReturn(Optional.of(setting));
        givenStock();
        service = new PublicService(gradeRepository, settingRepository, lotService);
    }

    @Test
    void onlyActiveGradesAreShownWithoutAPrice() {
        Grade big = new Grade();
        big.setCode("A");
        big.setName("Big");
        big.setNameRw("Binini");
        big.setDescription("Large potatoes");
        big.setSizeDescription("Bigger than a fist");
        big.setPackaging("50 kg bags");
        big.setFloorPricePerKg(350L);
        when(gradeRepository.findByActiveTrueOrderByCodeAsc()).thenReturn(List.of(big));
        givenStock(new StockResponse.GradeStock("Big", new BigDecimal("750.00")));

        assertEquals(List.of(new PublicGradeResponse("A", "Big", "Binini", "Large potatoes", "Bigger than a fist",
                "50 kg bags", StockAvailability.AVAILABLE)), service.grades("203.0.113.5"));
    }

    // --- Stock hint ---

    @Test
    void eachGradeGetsItsLevelFromTheStockPageNumbers() {
        setting.setMinOrderKg(new BigDecimal("100"));
        givenGrades("Big", "Normal", "Small");
        givenStock(new StockResponse.GradeStock("Big", new BigDecimal("1200.00")),
                new StockResponse.GradeStock("Normal", new BigDecimal("250.00")),
                new StockResponse.GradeStock("Small", new BigDecimal("40.00")));

        assertEquals(List.of(StockAvailability.AVAILABLE, StockAvailability.LIMITED, StockAvailability.NONE),
                service.grades("203.0.113.5").stream().map(PublicGradeResponse::availability).toList());
    }

    @Test
    void exactlyTheMinimumOrderIsLimitedAndJustBelowIsNone() {
        setting.setMinOrderKg(new BigDecimal("100"));
        givenGrades("Big", "Normal");
        givenStock(new StockResponse.GradeStock("Big", new BigDecimal("100.00")),
                new StockResponse.GradeStock("Normal", new BigDecimal("99.99")));

        assertEquals(List.of(StockAvailability.LIMITED, StockAvailability.NONE),
                service.grades("203.0.113.5").stream().map(PublicGradeResponse::availability).toList());
    }

    @Test
    void exactly500KgIsAvailableAndJustBelowIsLimited() {
        setting.setMinOrderKg(new BigDecimal("100"));
        givenGrades("Big", "Normal");
        givenStock(new StockResponse.GradeStock("Big", new BigDecimal("500.00")),
                new StockResponse.GradeStock("Normal", new BigDecimal("499.99")));

        assertEquals(List.of(StockAvailability.AVAILABLE, StockAvailability.LIMITED),
                service.grades("203.0.113.5").stream().map(PublicGradeResponse::availability).toList());
    }

    @Test
    void gradeWithNoLotsHasNoStock() {
        givenGrades("Big", "Small");
        givenStock(new StockResponse.GradeStock("Big", new BigDecimal("800.00")));

        assertEquals(List.of(StockAvailability.AVAILABLE, StockAvailability.NONE),
                service.grades("203.0.113.5").stream().map(PublicGradeResponse::availability).toList());
    }

    @Test
    void aMinimumOrderAbove500MakesSmallerStockNone() {
        setting.setMinOrderKg(new BigDecimal("600"));
        givenGrades("Big", "Normal");
        givenStock(new StockResponse.GradeStock("Big", new BigDecimal("550.00")),
                new StockResponse.GradeStock("Normal", new BigDecimal("600.00")));

        assertEquals(List.of(StockAvailability.NONE, StockAvailability.AVAILABLE),
                service.grades("203.0.113.5").stream().map(PublicGradeResponse::availability).toList());
    }

    @Test
    void gradesAreLimitedPerAddress() {
        givenGrades("Big");
        for (int i = 0; i < 60; i++) {
            service.grades("203.0.113.5");
        }

        TooManyRequestsException e = assertThrows(TooManyRequestsException.class,
                () -> service.grades("203.0.113.5"));

        assertTrue(e.getRetryAfterSeconds() > 0);
        assertEquals(1, service.grades("203.0.113.6").size());
    }

    private void givenGrades(String... names) {
        List<Grade> grades = new java.util.ArrayList<>();
        char code = 'A';
        for (String name : names) {
            Grade grade = new Grade();
            grade.setCode(String.valueOf(code++));
            grade.setName(name);
            grades.add(grade);
        }
        when(gradeRepository.findByActiveTrueOrderByCodeAsc()).thenReturn(grades);
    }

    private void givenStock(StockResponse.GradeStock... byGrade) {
        when(lotService.stock()).thenReturn(new StockResponse(new BigDecimal("999.00"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, List.of(byGrade)));
    }

    @Test
    void infoHasOnlyTheContactDetailsAndTheMinimumOrder() {
        Setting setting = new Setting();
        setting.setLocation("Busogo, Musanze");
        setting.setCooperativeEmail("info@intagamburuzwa.rw");
        setting.setCooperativePhone("+250788123456");
        setting.setMinOrderKg(new BigDecimal("250"));
        when(settingRepository.findById(1L)).thenReturn(Optional.of(setting));

        assertEquals(new PublicInfoResponse("Koperative Intagamburuzwa", "Busogo, Musanze", "info@intagamburuzwa.rw",
                new BigDecimal("250"), "+250788123456"), service.info());
    }

    @Test
    void missingSettingsRowIsAnError() {
        when(settingRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class, () -> service.info());
    }
}
