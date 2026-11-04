package com.coopaggregate.loss;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.batch.BatchAllocator;
import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;

import jakarta.persistence.EntityNotFoundException;

@Service
public class LossService {

    // A lot being filled or full, and the lots from before fixed-size lots that were never sold out.
    private static final Set<LotStatus> HOLDS_KG = Set.of(LotStatus.FILLING, LotStatus.FULL, LotStatus.OPEN,
            LotStatus.CLOSED);

    private final LossRepository lossRepository;
    private final LotRepository lotRepository;
    private final GradeRepository gradeRepository;
    private final LedgerService ledgerService;
    private final BatchAllocator batchAllocator;

    public LossService(LossRepository lossRepository, LotRepository lotRepository, GradeRepository gradeRepository,
                       LedgerService ledgerService, BatchAllocator batchAllocator) {
        this.lossRepository = lossRepository;
        this.lotRepository = lotRepository;
        this.gradeRepository = gradeRepository;
        this.ledgerService = ledgerService;
        this.batchAllocator = batchAllocator;
    }

    /**
     * Records a loss after intake (rot, damage, theft...) on a lot that still holds kg. It belongs to the members whose
     * confirmed potatoes are in the lot, shared in proportion to what each has left (BatchAllocator); kg already sold
     * never carry a loss.
     */
    @Transactional
    public LossResponse record(Long lotId, LossRequest request, Manager manager) {
        // The same order of locks as sales, confirmations and reversals: the grade, then the lot. Losses and sales of
        // the grade wait for each other, so the kg checked below cannot be used up by another request at the same time.
        Long gradeId = lotRepository.findGradeIdById(lotId)
                .orElseThrow(() -> new EntityNotFoundException("Lot " + lotId + " does not exist."));
        gradeRepository.findForUpdateById(gradeId);
        Lot lot = lotRepository.findForUpdateById(lotId).orElseThrow();
        if (!HOLDS_KG.contains(lot.getStatus())) {
            throw new IllegalStateException("Lot " + lot.getLotCode() + " is " + lot.getStatus()
                    + ". Losses can only be recorded on lots that still hold potatoes.");
        }

        BigDecimal carryingKg = batchAllocator.lossCarryingKg(lot);
        if (request.quantityKg().compareTo(carryingKg) > 0) {
            throw new BadRequestException("Only " + carryingKg.toPlainString() + " kg of confirmed potatoes remain in "
                    + "lot " + lot.getLotCode() + ".");
        }

        Loss loss = new Loss();
        loss.setLot(lot);
        loss.setQuantityKg(request.quantityKg());
        loss.setReason(request.reason());
        loss.setNotes(request.notes() == null || request.notes().isBlank() ? null : request.notes().trim());
        loss.setLossDate(request.recordedAt() != null ? request.recordedAt() : KigaliTime.today());
        Loss saved = lossRepository.save(loss);
        batchAllocator.allocateLoss(saved);

        ledgerService.record(LedgerEntryType.LOSS, "LOSS", saved.getId(), saved.getQuantityKg(), null,
                "Loss from " + lot.getLotCode() + ": " + saved.getReason(), manager);
        return LossResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<LossResponse> list(Long lotId) {
        if (!lotRepository.existsById(lotId)) {
            throw new EntityNotFoundException("Lot " + lotId + " does not exist.");
        }
        return lossRepository.findByLotIdOrderByLossDateDescIdDesc(lotId).stream()
                .map(LossResponse::from)
                .toList();
    }
}
