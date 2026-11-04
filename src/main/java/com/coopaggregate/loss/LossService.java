package com.coopaggregate.loss;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.batch.BatchAllocator;
import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;

import jakarta.persistence.EntityNotFoundException;

@Service
public class LossService {

    private final LossRepository lossRepository;
    private final LotRepository lotRepository;
    private final LotService lotService;
    private final LedgerService ledgerService;
    private final BatchAllocator batchAllocator;

    public LossService(LossRepository lossRepository, LotRepository lotRepository, LotService lotService,
                       LedgerService ledgerService, BatchAllocator batchAllocator) {
        this.lossRepository = lossRepository;
        this.lotRepository = lotRepository;
        this.lotService = lotService;
        this.ledgerService = ledgerService;
        this.batchAllocator = batchAllocator;
    }

    @Transactional
    public LossResponse record(Long lotId, LossRequest request, Manager manager) {
        // The row lock makes losses and sales of the same lot wait for each other, so the remaining kg
        // checked below cannot be used up by another request at the same time.
        Lot lot = lotRepository.findForUpdateById(lotId)
                .orElseThrow(() -> new EntityNotFoundException("Lot " + lotId + " does not exist."));
        if (lot.getStatus() != LotStatus.OPEN && lot.getStatus() != LotStatus.CLOSED) {
            throw new IllegalStateException("Lot " + lot.getLotCode() + " is " + lot.getStatus()
                    + ". Losses can only be recorded on open or closed lots.");
        }

        BigDecimal remainingKg = lotService.quantitiesOf(lot).remainingKg();
        if (request.quantityKg().compareTo(remainingKg) > 0) {
            throw new BadRequestException("Only " + remainingKg.toPlainString() + " kg remain in lot "
                    + lot.getLotCode() + ".");
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
