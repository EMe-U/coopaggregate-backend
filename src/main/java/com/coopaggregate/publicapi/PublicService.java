package com.coopaggregate.publicapi;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.setting.SettingRepository;

@Service
public class PublicService {

    private final GradeRepository gradeRepository;
    private final SettingRepository settingRepository;

    public PublicService(GradeRepository gradeRepository, SettingRepository settingRepository) {
        this.gradeRepository = gradeRepository;
        this.settingRepository = settingRepository;
    }

    @Transactional(readOnly = true)
    public List<PublicGradeResponse> grades() {
        return gradeRepository.findByActiveTrueOrderByCodeAsc().stream().map(PublicGradeResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public PublicInfoResponse info() {
        return PublicInfoResponse.from(settingRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("The settings row is missing.")));
    }
}
