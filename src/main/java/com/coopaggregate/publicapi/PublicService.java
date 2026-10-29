package com.coopaggregate.publicapi;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.StockResponse;
import com.coopaggregate.setting.Setting;
import com.coopaggregate.setting.SettingRepository;

@Service
public class PublicService {

    private final GradeRepository gradeRepository;
    private final SettingRepository settingRepository;
    private final LotService lotService;
    private final RateLimiter gradesPerIp = new RateLimiter(60, Duration.ofMinutes(1));

    public PublicService(GradeRepository gradeRepository, SettingRepository settingRepository,
                         LotService lotService) {
        this.gradeRepository = gradeRepository;
        this.settingRepository = settingRepository;
        this.lotService = lotService;
    }

    /**
     * Active grades with a stock hint. The hint is worked out from the sellable kg of the grade (confirmed batches
     * with kg left), so a buyer is never promised kg that cannot be sold. The kilograms are not shown.
     */
    @Transactional(readOnly = true)
    public List<PublicGradeResponse> grades(String clientIp) {
        long wait = gradesPerIp.tryAcquire("ip:" + clientIp);
        if (wait > 0) {
            throw new TooManyRequestsException(wait);
        }
        BigDecimal minOrderKg = setting().getMinOrderKg();
        // The stock page lists the sellable kg per grade name; a grade with no kg left is not in it. Kg that are
        // waiting for the member's confirmation or disputed are left out: they cannot be sold yet.
        Map<String, BigDecimal> sellableByGrade = lotService.stock().byGrade().stream()
                .collect(Collectors.toMap(StockResponse.GradeStock::gradeName,
                        StockResponse.GradeStock::sellableKg, BigDecimal::add));
        return gradeRepository.findByActiveTrueOrderByCodeAsc().stream()
                .map(grade -> PublicGradeResponse.from(grade, StockAvailability.of(
                        sellableByGrade.getOrDefault(grade.getName(), BigDecimal.ZERO), minOrderKg)))
                .toList();
    }

    @Transactional(readOnly = true)
    public PublicInfoResponse info() {
        return PublicInfoResponse.from(setting());
    }

    private Setting setting() {
        return settingRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("The settings row is missing."));
    }
}
