package com.coopaggregate.batch;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.setting.SettingRepository;

import jakarta.persistence.EntityNotFoundException;

/** Shows which batches a sale of a grade would use and what each member would be credited. Saves nothing. */
@Service
public class SalePreviewService {

    private static final BigDecimal MAX_KG = new BigDecimal("9999999999.99");

    private final BatchRepository batchRepository;
    private final GradeRepository gradeRepository;
    private final SettingRepository settingRepository;

    public SalePreviewService(BatchRepository batchRepository, GradeRepository gradeRepository,
                              SettingRepository settingRepository) {
        this.batchRepository = batchRepository;
        this.gradeRepository = gradeRepository;
        this.settingRepository = settingRepository;
    }

    @Transactional(readOnly = true)
    public SalePreviewResponse preview(Long gradeId, BigDecimal quantityKg, Long pricePerKg, List<Long> skipIds) {
        checkQuantity(quantityKg);
        if (pricePerKg == null || pricePerKg <= 0) {
            throw new BadRequestException("pricePerKg is required and must be more than 0.");
        }
        Grade grade = gradeRepository.findById(gradeId)
                .orElseThrow(() -> new EntityNotFoundException("Grade " + gradeId + " does not exist."));
        long deductionPerKg = settingRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("The settings row is missing."))
                .getDeductionPerKg();

        List<SellableBatch> sellable = batchRepository.findSellableBatches(gradeId).stream()
                .map(SellableBatch::from)
                .toList();
        SalePlan plan = SalePlanner.plan(sellable, quantityKg, pricePerKg, deductionPerKg,
                skipIds == null ? List.of() : skipIds);
        BigDecimal waitingKg = batchRepository.gradeRemainingKgWithStatus(gradeId, ConfirmationStatus.PENDING);
        BigDecimal disputedKg = batchRepository.gradeRemainingKgWithStatus(gradeId, ConfirmationStatus.DISPUTED);

        Long floorPricePerKg = grade.getFloorPricePerKg();
        return new SalePreviewResponse(grade.getId(), grade.getName(), quantityKg, pricePerKg,
                floorPricePerKg != null && pricePerKg < floorPricePerKg, deductionPerKg, plan.sellableKg(),
                waitingKg, disputedKg, plan.enough(),
                plan.enough() ? null : plan.shortageMessage(grade.getName(), waitingKg),
                plan.totalAmountRwf(), plan.totalDeductionRwf(), plan.totalNetRwf(),
                plan.lines().stream().map(SalePreviewService::line).toList(),
                plan.skipped().stream().map(SalePreviewService::skipped).toList());
    }

    // The same limits as a sale's quantity: more than 0, at most 2 decimals and 10 digits before the point.
    private static void checkQuantity(BigDecimal quantityKg) {
        if (quantityKg == null || quantityKg.signum() <= 0) {
            throw new BadRequestException("quantityKg is required and must be more than 0.");
        }
        if (quantityKg.stripTrailingZeros().scale() > 2 || quantityKg.compareTo(MAX_KG) > 0) {
            throw new BadRequestException("quantityKg must have at most 2 decimals and 10 digits before the point.");
        }
    }

    private static SalePreviewResponse.Line line(SalePlan.Line line) {
        SellableBatch batch = line.batch();
        return new SalePreviewResponse.Line(batch.deliveryId(), batch.receiptCode(), batch.lotId(), batch.lotCode(),
                batch.memberId(), batch.memberNumber(), batch.memberName(), batch.deliveredAt(), line.kg(),
                line.remainingAfterKg(), line.grossRwf(), line.deductionRwf(), line.netRwf());
    }

    private static SalePreviewResponse.Skipped skipped(SellableBatch batch) {
        return new SalePreviewResponse.Skipped(batch.deliveryId(), batch.receiptCode(), batch.lotCode(),
                batch.memberName(), batch.deliveredAt(), batch.remainingKg());
    }
}
