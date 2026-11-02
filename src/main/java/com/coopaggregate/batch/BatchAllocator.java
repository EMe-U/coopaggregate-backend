package com.coopaggregate.batch;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.loss.Loss;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotPortion;
import com.coopaggregate.lot.LotPortionRepository;
import com.coopaggregate.lot.ProportionalKg;

/**
 * Keeps the lot portions in step with the lot's losses. A loss belongs to the members whose potatoes are in the lot:
 * it is shared across the lot's portions in proportion to what each has left (see ProportionalKg), and each part is
 * recorded as a loss allocation of that portion. Only confirmed kg carry a loss, disputed ones included because they are
 * still in the lot; kg already sold never do, and neither do kg still waiting for their member (only lots from before
 * fixed-size lots hold any). The caller must hold the grade's lock and the lot's; this locks the portions and their
 * deliveries next, so the order stays grade, lot, portions, deliveries, ledger.
 */
@Component
public class BatchAllocator {

    private static final Comparator<LotPortion> OLDEST_FIRST = Comparator
            .comparing((LotPortion portion) -> portion.getDelivery().getDeliveredAt())
            .thenComparing(portion -> portion.getDelivery().getId())
            .thenComparing(LotPortion::getId);

    private final LotPortionRepository lotPortionRepository;
    private final LossAllocationRepository lossAllocationRepository;
    private final DeliveryRepository deliveryRepository;

    public BatchAllocator(LotPortionRepository lotPortionRepository, LossAllocationRepository lossAllocationRepository,
                          DeliveryRepository deliveryRepository) {
        this.lotPortionRepository = lotPortionRepository;
        this.lossAllocationRepository = lossAllocationRepository;
        this.deliveryRepository = deliveryRepository;
    }

    /** The kg of the lot that a loss can be taken from now. */
    public BigDecimal lossCarryingKg(Lot lot) {
        return carriers(lot, null).stream().map(LotPortion::unallocatedKg).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public void allocateLoss(Loss loss) {
        spread(loss, carriers(loss.getLot(), null), loss.getQuantityKg());
    }

    /**
     * A reversed delivery is no batch any more. Its lost kg go to the other portions of the same lots, in proportion to
     * what each has left, so each lot's lost kg stay the same. It cannot hold sold kg: a delivery with sold kg cannot be
     * reversed.
     */
    public void releaseLosses(Delivery reversed) {
        if (reversed.getLostKg().signum() == 0) {
            return;
        }
        List<LossAllocation> held = lossAllocationRepository.findByDeliveryIdOrderById(reversed.getId());
        reversed.releaseLostKg();
        lotPortionRepository.findByDeliveryIdOrderById(reversed.getId()).forEach(LotPortion::releaseLostKg);
        lossAllocationRepository.deleteAll(held);
        for (LossAllocation allocation : held) {
            Loss loss = allocation.getLoss();
            spread(loss, carriers(loss.getLot(), reversed), allocation.getQuantityKg());
        }
    }

    // Shares kg of the loss across the portions in proportion to what each has left, oldest delivery first on a tie.
    private void spread(Loss loss, List<LotPortion> portions, BigDecimal kg) {
        BigDecimal carrying = portions.stream().map(LotPortion::unallocatedKg).reduce(BigDecimal.ZERO, BigDecimal::add);
        // The checks before this make it impossible; if it ever happens, the whole change is rolled back.
        if (kg.compareTo(carrying) > 0) {
            throw new IllegalStateException("Lot " + loss.getLot().getLotCode() + " holds only "
                    + carrying.toPlainString() + " kg that can carry a loss, not " + kg.toPlainString()
                    + " kg. Nothing was saved.");
        }
        List<BigDecimal> parts = ProportionalKg.split(portions.stream().map(LotPortion::unallocatedKg).toList(), kg);
        for (int i = 0; i < portions.size(); i++) {
            if (parts.get(i).signum() > 0) {
                addLoss(loss, portions.get(i), parts.get(i));
            }
        }
    }

    private void addLoss(Loss loss, LotPortion portion, BigDecimal kg) {
        portion.addLostKg(kg);
        portion.getDelivery().addLostKg(kg);
        lossAllocationRepository.findByLossIdAndDeliveryId(loss.getId(), portion.getDelivery().getId())
                .ifPresentOrElse(existing -> existing.addKg(kg), () -> {
                    LossAllocation allocation = new LossAllocation(loss, portion.getDelivery(), kg);
                    allocation.setLotPortion(portion);
                    lossAllocationRepository.save(allocation);
                });
    }

    // The lot's portions that can carry a loss, oldest delivery first, locked with their deliveries. leftOut is a
    // delivery being reversed, whose portions no longer count.
    private List<LotPortion> carriers(Lot lot, Delivery leftOut) {
        List<LotPortion> portions = lotPortionRepository.findLossCarriers(lot.getId()).stream()
                .filter(portion -> portion.getDelivery() != leftOut && !portion.getDelivery().isReversed()
                        && portion.unallocatedKg().signum() > 0
                        && (portion.getDelivery().getConfirmationStatus() == ConfirmationStatus.CONFIRMED
                                || portion.getDelivery().getConfirmationStatus() == ConfirmationStatus.DISPUTED))
                .sorted(OLDEST_FIRST)
                .toList();
        if (!portions.isEmpty()) {
            lotPortionRepository.lockByIds(portions.stream().map(LotPortion::getId).sorted().toList());
            deliveryRepository.lockByIds(portions.stream().map(portion -> portion.getDelivery().getId())
                    .distinct().sorted().toList());
        }
        return portions;
    }
}
