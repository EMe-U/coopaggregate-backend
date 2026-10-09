package com.coopaggregate.lot;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;

import jakarta.persistence.EntityNotFoundException;

@Service
public class LotService {

    private final LotRepository lotRepository;
    private final GradeRepository gradeRepository;

    public LotService(LotRepository lotRepository, GradeRepository gradeRepository) {
        this.lotRepository = lotRepository;
        this.gradeRepository = gradeRepository;
    }

    @Transactional(readOnly = true)
    public Optional<OpenLotResponse> findOpenLot(Long gradeId) {
        if (!gradeRepository.existsById(gradeId)) {
            throw new EntityNotFoundException("Grade " + gradeId + " does not exist.");
        }
        return lotRepository.findFirstByGradeIdAndStatus(gradeId, LotStatus.OPEN)
                .map(lot -> new OpenLotResponse(lot.getId(), lot.getLotCode(), lot.getGrade().getName(),
                        lotRepository.totalDeliveredKg(lot.getId())));
    }

    /**
     * Returns the open lot for the grade, or opens a new one. The caller must hold the grade's
     * row lock (GradeRepository.findForUpdateById), so two deliveries cannot open two lots at once.
     */
    @Transactional
    public Lot findOrOpenLot(Grade grade) {
        return lotRepository.findFirstByGradeAndStatus(grade, LotStatus.OPEN)
                .orElseGet(() -> openLot(grade));
    }

    // Lot code: grade code, year the lot opened, and the number of that grade's lot in that year,
    // for example A-2026-01 for the first grade A lot of 2026.
    private Lot openLot(Grade grade) {
        LocalDate today = KigaliTime.today();
        String prefix = grade.getCode() + "-" + today.getYear() + "-";
        long lotsThisYear = lotRepository.countByLotCodeStartingWith(prefix);

        Lot lot = new Lot();
        lot.setLotCode(prefix + String.format("%02d", lotsThisYear + 1));
        lot.setGrade(grade);
        lot.setOpenDate(today);
        lot.setStatus(LotStatus.OPEN);
        return lotRepository.save(lot);
    }
}
