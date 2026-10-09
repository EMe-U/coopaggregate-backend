package com.coopaggregate.lot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.common.PageResponse;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;

@Service
public class LotService {

    private static final int MAX_PAGE_SIZE = 100;

    private final LotRepository lotRepository;
    private final GradeRepository gradeRepository;

    public LotService(LotRepository lotRepository, GradeRepository gradeRepository) {
        this.lotRepository = lotRepository;
        this.gradeRepository = gradeRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<LotResponse> list(LotStatus status, Long gradeId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "id"));
        Page<Lot> lots = lotRepository.findAll(matching(status, gradeId), pageRequest);
        Map<Long, LotQuantities> quantities = quantitiesOf(lots.getContent());
        return PageResponse.from(lots.map(lot -> LotResponse.from(lot, quantities.get(lot.getId()))));
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

    Map<Long, LotQuantities> quantitiesOf(List<Lot> lots) {
        if (lots.isEmpty()) {
            return Map.of();
        }
        return lotRepository.findQuantities(lots.stream().map(Lot::getId).toList()).stream()
                .collect(Collectors.toMap(LotQuantities::lotId, Function.identity()));
    }

    private static Specification<Lot> matching(LotStatus status, Long gradeId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (gradeId != null) {
                predicates.add(cb.equal(root.get("grade").get("id"), gradeId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
