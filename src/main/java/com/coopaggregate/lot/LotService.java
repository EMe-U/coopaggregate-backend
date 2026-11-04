package com.coopaggregate.lot;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
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
    public LotDetailResponse get(Long id) {
        LotResponse lot = toResponse(findLot(id));
        return LotDetailResponse.from(lot, lotRepository.findMembers(id));
    }

    @Transactional
    public LotResponse close(Long id) {
        Long gradeId = lotRepository.findGradeIdById(id)
                .orElseThrow(() -> new EntityNotFoundException("Lot " + id + " does not exist."));
        // Deliveries lock the grade too. Taking that lock before reading the lot means a delivery in progress
        // finishes in this lot first, and a later delivery finds no open lot and starts a new one.
        gradeRepository.findForUpdateById(gradeId);

        Lot lot = findLot(id);
        if (lot.getStatus() != LotStatus.OPEN) {
            throw new IllegalStateException("Lot " + lot.getLotCode() + " is " + lot.getStatus()
                    + ". Only an open lot can be closed.");
        }
        lot.setStatus(LotStatus.CLOSED);
        lot.setCloseDate(KigaliTime.today());
        return toResponse(lot);
    }

    // The kg left are read per grade from the batches, whatever their lot, split into sellable (confirmed), waiting
    // (pending) and disputed kg. The delivered, sold and lost totals cover all lots, so delivered - lost - sold still
    // equals the remaining kg. The lot counts below are only for information: a lot does not have to be closed to be
    // sold.
    @Transactional(readOnly = true)
    public StockResponse stock() {
        List<Lot> lots = lotRepository.findByStatusIn(List.of(LotStatus.OPEN, LotStatus.CLOSED));

        // By grade code, so the grades are listed in the same order as before.
        Map<String, String> gradeNameByCode = new TreeMap<>();
        Map<String, BigDecimal[]> kgByGradeCode = new TreeMap<>();
        BigDecimal totalSellableKg = BigDecimal.ZERO;
        BigDecimal totalWaitingKg = BigDecimal.ZERO;
        BigDecimal totalDisputedKg = BigDecimal.ZERO;
        for (GradeKgLeft left : lotRepository.findKgLeftByGradeAndStatus()) {
            gradeNameByCode.put(left.gradeCode(), left.gradeName());
            BigDecimal[] kg = kgByGradeCode.computeIfAbsent(left.gradeCode(),
                    code -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            switch (left.status()) {
                case CONFIRMED -> {
                    kg[0] = kg[0].add(left.kg());
                    totalSellableKg = totalSellableKg.add(left.kg());
                }
                case PENDING -> {
                    kg[1] = kg[1].add(left.kg());
                    totalWaitingKg = totalWaitingKg.add(left.kg());
                }
                case DISPUTED -> {
                    kg[2] = kg[2].add(left.kg());
                    totalDisputedKg = totalDisputedKg.add(left.kg());
                }
            }
        }
        List<StockResponse.GradeStock> byGrade = kgByGradeCode.entrySet().stream()
                .map(entry -> new StockResponse.GradeStock(gradeNameByCode.get(entry.getKey()),
                        entry.getValue()[0].add(entry.getValue()[1]).add(entry.getValue()[2]),
                        entry.getValue()[0], entry.getValue()[1], entry.getValue()[2]))
                .toList();
        BigDecimal totalRemainingKg = totalSellableKg.add(totalWaitingKg).add(totalDisputedKg);
        long openLots = lots.stream().filter(lot -> lot.getStatus() == LotStatus.OPEN).count();

        Instant startOfToday = KigaliTime.today().atStartOfDay(KigaliTime.ZONE).toInstant();
        Instant startOfTomorrow = KigaliTime.today().plusDays(1).atStartOfDay(KigaliTime.ZONE).toInstant();
        return new StockResponse(
                totalRemainingKg,
                totalSellableKg,
                totalWaitingKg,
                totalDisputedKg,
                lotRepository.totalDeliveredKg(),
                lotRepository.totalSoldKg(),
                lotRepository.totalLostKg(),
                lotRepository.totalPendingConfirmationKg(),
                lotRepository.deliveredKgBetween(startOfToday, startOfTomorrow),
                lotRepository.deliveryCountBetween(startOfToday, startOfTomorrow),
                openLots,
                lots.size() - openLots,
                byGrade);
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

    Lot findLot(Long id) {
        return lotRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Lot " + id + " does not exist."));
    }

    public LotQuantities quantitiesOf(Lot lot) {
        LotQuantities quantities = quantitiesOf(List.of(lot)).get(lot.getId());
        return quantities != null ? quantities : LotQuantities.empty(lot.getId());
    }

    LotResponse toResponse(Lot lot) {
        return LotResponse.from(lot, quantitiesOf(lot));
    }

    public Map<Long, LotQuantities> quantitiesOf(List<Lot> lots) {
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
