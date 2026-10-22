package com.coopaggregate.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.setting.Setting;
import com.coopaggregate.setting.SettingRepository;

class PublicServiceTest {

    private GradeRepository gradeRepository;
    private SettingRepository settingRepository;
    private PublicService service;

    @BeforeEach
    void setUp() {
        gradeRepository = mock(GradeRepository.class);
        settingRepository = mock(SettingRepository.class);
        service = new PublicService(gradeRepository, settingRepository);
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

        assertEquals(List.of(new PublicGradeResponse("A", "Big", "Binini", "Large potatoes", "Bigger than a fist",
                "50 kg bags")), service.grades());
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
