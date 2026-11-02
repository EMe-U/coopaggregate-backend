package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.setting.SettingRepository;

/**
 * Puts a confirmed delivery into the fixed-size lots of its grade. One lot per grade is FILLING at a time: the delivery
 * fills it up to its capacity, the lot becomes FULL, and what is left starts the next lot. Lots are filled in the order
 * deliveries are confirmed, so a delivery confirmed late goes into the lot being filled then.
 *
 * The caller must hold the grade's row lock (GradeRepository.findForUpdateById) and the delivery's, in that order: the
 * grade lock is what keeps two confirmations of the same grade from filling one lot past its capacity. This takes the
 * filling lot's lock next, so the order stays grade, lot, delivery, ledger.
 */
@Component
public class LotFiller {

    private final LotRepository lotRepository;
    private final LotPortionRepository lotPortionRepository;
    private final SettingRepository settingRepository;

    public LotFiller(LotRepository lotRepository, LotPortionRepository lotPortionRepository,
                     SettingRepository settingRepository) {
        this.lotRepository = lotRepository;
        this.lotPortionRepository = lotPortionRepository;
        this.settingRepository = settingRepository;
    }

    /**
     * Locks the grade's lot being filled, if there is one. Callers that are about to confirm a delivery take it after
     * the grade and before the delivery, so the order of locks is the same as for sales and losses.
     */
    public void lockFillingLot(Long gradeId) {
        lotRepository.findFillingForUpdate(gradeId);
    }

    /** Whether the delivery has kg in any lot. */
    public boolean isInLots(Delivery delivery) {
        return lotPortionRepository.existsByDeliveryId(delivery.getId());
    }

    /**
     * Returns the portions the delivery was given, oldest lot first. Nothing happens for a delivery that is already in
     * lots (a dispute raised after confirmation and then resolved, or a delivery from before fixed-size lots).
     */
    public List<LotPortion> place(Delivery delivery) {
        if (delivery.isReversed() || delivery.getConfirmationStatus() != ConfirmationStatus.CONFIRMED) {
            throw new IllegalStateException("Only a confirmed delivery goes into a lot.");
        }
        if (lotPortionRepository.existsByDeliveryId(delivery.getId())) {
            return List.of();
        }

        Grade grade = delivery.getGrade();
        List<LotPortion> placed = new ArrayList<>();
        BigDecimal left = delivery.getQuantityKg();
        Lot lot = lotRepository.findFillingForUpdate(grade.getId()).orElseGet(() -> openLot(grade));
        BigDecimal space = lot.getCapacityKg().subtract(lotPortionRepository.filledKg(lot.getId()));
        if (space.signum() <= 0) {
            // Cannot happen: a lot is marked FULL as soon as it holds its capacity. Never fill past it if it does.
            lot.setStatus(LotStatus.FULL);
            lot.setCloseDate(KigaliTime.today());
            lot = openLot(grade);
            space = lot.getCapacityKg();
        }
        while (left.signum() > 0) {
            BigDecimal kg = left.min(space);
            placed.add(lotPortionRepository.save(new LotPortion(lot, delivery, kg)));
            left = left.subtract(kg);
            space = space.subtract(kg);
            if (space.signum() == 0) {
                lot.setStatus(LotStatus.FULL);
                lot.setCloseDate(KigaliTime.today());
                if (left.signum() > 0) {
                    lot = openLot(grade);
                    space = lot.getCapacityKg();
                }
            }
        }
        return placed;
    }

    // Lot code: grade code, year the lot opened, and the number of that grade's lot in that year, for example A-2026-01
    // for the first grade A lot of 2026. Lots from before fixed-size lots are counted too, so codes never repeat.
    private Lot openLot(Grade grade) {
        LocalDate today = KigaliTime.today();
        String prefix = grade.getCode() + "-" + today.getYear() + "-";
        long lotsThisYear = lotRepository.countByLotCodeStartingWith(prefix);

        Lot lot = new Lot();
        lot.setLotCode(prefix + String.format("%02d", lotsThisYear + 1));
        lot.setGrade(grade);
        lot.setOpenDate(today);
        lot.setStatus(LotStatus.FILLING);
        lot.setCapacityKg(settingRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("The settings row is missing."))
                .getLotSizeKg());
        return lotRepository.save(lot);
    }
}
