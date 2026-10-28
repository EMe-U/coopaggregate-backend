package com.coopaggregate.batch;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.BiConsumer;

import org.springframework.stereotype.Component;

import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.loss.Loss;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.sale.Sale;

/**
 * Keeps the batches (deliveries) in step with the lot: a sale or loss takes its kg from the lot's oldest batches
 * first (by delivered time, then id) and records which ones. Money and lot totals do not depend on this; the sum of
 * a lot's batches always equals the lot's own remaining kg. The caller must hold the lot's lock.
 */
@Component
public class BatchAllocator {

    private final BatchRepository batchRepository;
    private final SaleAllocationRepository saleAllocationRepository;
    private final LossAllocationRepository lossAllocationRepository;

    public BatchAllocator(BatchRepository batchRepository, SaleAllocationRepository saleAllocationRepository,
                          LossAllocationRepository lossAllocationRepository) {
        this.batchRepository = batchRepository;
        this.saleAllocationRepository = saleAllocationRepository;
        this.lossAllocationRepository = lossAllocationRepository;
    }

    public void allocateSale(Sale sale) {
        take(sale.getLot(), sale.getQuantityKg(), (batch, kg) -> {
            batch.addSoldKg(kg);
            saleAllocationRepository.save(new SaleAllocation(sale, batch, kg));
        });
    }

    public void allocateLoss(Loss loss) {
        take(loss.getLot(), loss.getQuantityKg(), (batch, kg) -> addLoss(loss, batch, kg));
    }

    /**
     * A reversed delivery is no batch any more. Its lost kg go to the lot's other batches, oldest first, so the lot's
     * lost kg stay the same. It cannot hold sold kg: a delivery whose lot has sales cannot be reversed.
     */
    public void releaseLosses(Delivery reversed) {
        if (reversed.getLostKg().signum() == 0) {
            return;
        }
        List<LossAllocation> held = lossAllocationRepository.findByDeliveryIdOrderById(reversed.getId());
        reversed.releaseLostKg();
        lossAllocationRepository.deleteAll(held);
        for (LossAllocation allocation : held) {
            take(reversed.getLot(), allocation.getQuantityKg(),
                    (batch, kg) -> addLoss(allocation.getLoss(), batch, kg));
        }
    }

    private void addLoss(Loss loss, Delivery batch, BigDecimal kg) {
        batch.addLostKg(kg);
        lossAllocationRepository.findByLossIdAndDeliveryId(loss.getId(), batch.getId())
                .ifPresentOrElse(existing -> existing.addKg(kg),
                        () -> lossAllocationRepository.save(new LossAllocation(loss, batch, kg)));
    }

    // Oldest batch first, as much as each has left, until the kg are covered.
    private void take(Lot lot, BigDecimal kgNeeded, BiConsumer<Delivery, BigDecimal> use) {
        BigDecimal left = kgNeeded;
        for (Delivery batch : batchRepository.lockBatchesWithKgLeft(lot.getId())) {
            if (left.signum() == 0) {
                break;
            }
            BigDecimal kg = batch.unallocatedKg().min(left);
            if (kg.signum() > 0) {
                use.accept(batch, kg);
                left = left.subtract(kg);
            }
        }
        // The lot check before this makes it impossible; if it ever happens, the whole change is rolled back.
        if (left.signum() > 0) {
            throw new IllegalStateException("The batches of lot " + lot.getLotCode() + " are " + left.toPlainString()
                    + " kg short of the lot's total. Nothing was saved.");
        }
    }
}
