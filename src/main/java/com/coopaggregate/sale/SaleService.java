package com.coopaggregate.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.batch.BatchRepository;
import com.coopaggregate.batch.SaleAllocation;
import com.coopaggregate.batch.SaleAllocationRepository;
import com.coopaggregate.batch.SalePlan;
import com.coopaggregate.batch.SalePlanner;
import com.coopaggregate.batch.SellableBatch;
import com.coopaggregate.buyer.Buyer;
import com.coopaggregate.buyer.BuyerRepository;
import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.common.PageResponse;
import com.coopaggregate.common.ShortCodes;
import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotPortion;
import com.coopaggregate.lot.LotPortionRepository;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.payment.PaymentMethod;
import com.coopaggregate.payment.PaymentReferences;
import com.coopaggregate.setting.SettingRepository;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/**
 * Sales are made by grade, first in, first out by lot: lots from before fixed-size lots first (oldest delivery first),
 * then the fixed-size lots in the order they were filled, each lot shared across its portions in proportion to what
 * each has left (see SalePlanner). Each portion's member is credited for the kg taken: their part of the sale amount
 * minus their part of the deduction. The locks are always taken in this order: the grade, every lot of the grade with
 * sellable kg (by id), their portions, the deliveries, and last the ledger.
 */
@Service
public class SaleService {

    private static final int MAX_PAGE_SIZE = 100;

    private final SaleRepository saleRepository;
    private final SaleOrderRepository saleOrderRepository;
    private final GradeRepository gradeRepository;
    private final LotRepository lotRepository;
    private final BuyerRepository buyerRepository;
    private final LedgerService ledgerService;
    private final BatchRepository batchRepository;
    private final SaleAllocationRepository saleAllocationRepository;
    private final SettingRepository settingRepository;
    private final LotPortionRepository lotPortionRepository;
    private final DeliveryRepository deliveryRepository;

    public SaleService(SaleRepository saleRepository, SaleOrderRepository saleOrderRepository,
                       GradeRepository gradeRepository, LotRepository lotRepository, BuyerRepository buyerRepository,
                       LedgerService ledgerService, BatchRepository batchRepository,
                       SaleAllocationRepository saleAllocationRepository, SettingRepository settingRepository,
                       LotPortionRepository lotPortionRepository, DeliveryRepository deliveryRepository) {
        this.saleRepository = saleRepository;
        this.saleOrderRepository = saleOrderRepository;
        this.gradeRepository = gradeRepository;
        this.lotRepository = lotRepository;
        this.buyerRepository = buyerRepository;
        this.ledgerService = ledgerService;
        this.batchRepository = batchRepository;
        this.saleAllocationRepository = saleAllocationRepository;
        this.settingRepository = settingRepository;
        this.lotPortionRepository = lotPortionRepository;
        this.deliveryRepository = deliveryRepository;
    }

    /** What one sale of one grade asks for. */
    private record Wanted(Grade grade, BigDecimal quantityKg, long pricePerKg) {
    }

    /** A sale worked out under the locks, with the locked portions it will take kg from. */
    private record Planned(Wanted wanted, SalePlan plan, Map<Long, LotPortion> portions) {
    }

    @Transactional
    public RecordedSale record(SaleRequest request, Manager manager) {
        Wanted asked = checkedWanted(request.quantityKg(), request.pricePerKg(), request.skipLotIds(),
                request.skipReason(), request.skipDeliveryIds());
        String reference = checkedReference(request.paymentMethod(), request.paymentReference());

        // The row lock makes sales of the same grade wait for each other, so two sales cannot both pass the check
        // for the same kg. It also makes a repeated client UUID wait for the first request.
        Map<Long, Grade> grades = lockGrades(List.of(request.gradeId()));
        Optional<Sale> existing = saleRepository.findByClientUuid(request.clientUuid());
        if (existing.isPresent()) {
            return new RecordedSale(responseOf(existing.get()), false);
        }
        checkReferenceUnused(request.paymentMethod(), reference);
        Buyer buyer = findBuyer(request.buyerId());
        Wanted wanted = withGrade(asked, grades.get(request.gradeId()));

        List<Planned> planned = planUnderLocks(List.of(wanted));
        LocalDate saleDate = request.soldAt() != null ? request.soldAt() : KigaliTime.today();
        // A sale of one grade is an order of its own, which keeps how the buyer paid. The sale holds the client
        // UUID, so the order gets a random one.
        SaleOrder order = saveOrder(buyer, UUID.randomUUID(), saleDate, request.paymentMethod(), reference);
        Sale saved = saveSale(planned.get(0), buyer, request.clientUuid(), order, saleDate, manager);
        updateLotStatuses(planned);
        return new RecordedSale(responseOf(saved), true);
    }

    @Transactional
    public RecordedSaleOrder recordOrder(SaleOrderRequest request, Manager manager) {
        List<Long> gradeIds = request.items().stream().map(SaleOrderRequest.Item::gradeId).toList();
        if (new HashSet<>(gradeIds).size() != gradeIds.size()) {
            throw new BadRequestException("Each grade can appear only once in an order.");
        }
        List<Wanted> asked = request.items().stream()
                .map(item -> checkedWanted(item.quantityKg(), item.pricePerKg(), item.skipLotIds(),
                        item.skipReason(), item.skipDeliveryIds()))
                .toList();
        String reference = checkedReference(request.paymentMethod(), request.paymentReference());

        // Locked in id order, so two orders that share grades always lock them in the same order and cannot
        // deadlock. The locks also make a repeated client UUID wait for the first request.
        Map<Long, Grade> grades = lockGrades(gradeIds);
        Optional<SaleOrder> existing = saleOrderRepository.findByClientUuid(request.clientUuid());
        if (existing.isPresent()) {
            return new RecordedSaleOrder(toResponse(existing.get()), false);
        }
        checkReferenceUnused(request.paymentMethod(), reference);
        Buyer buyer = findBuyer(request.buyerId());
        List<Wanted> wanted = new ArrayList<>();
        for (int i = 0; i < asked.size(); i++) {
            wanted.add(withGrade(asked.get(i), grades.get(request.items().get(i).gradeId())));
        }

        // Every item is planned before anything is saved, so a problem in any item saves nothing.
        List<Planned> planned = planUnderLocks(wanted);

        SaleOrder savedOrder = saveOrder(buyer, request.clientUuid(),
                request.soldAt() != null ? request.soldAt() : KigaliTime.today(), request.paymentMethod(), reference);

        List<Sale> savedSales = new ArrayList<>();
        for (Planned item : planned) {
            // The order holds the client UUID, so each sale row gets a random one of its own.
            savedSales.add(saveSale(item, buyer, UUID.randomUUID(), savedOrder, savedOrder.getSaleDate(), manager));
        }
        updateLotStatuses(planned);
        return new RecordedSaleOrder(SaleOrderResponse.from(savedOrder, responsesOf(savedSales)), true);
    }

    @Transactional(readOnly = true)
    public PageResponse<SaleResponse> list(String search, Long lotId, Long buyerId, Long gradeId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "saleDate", "id"));
        Page<Sale> sales = saleRepository.findAll(matching(search, lotId, buyerId, gradeId), pageRequest);
        return new PageResponse<>(responsesOf(sales.getContent()), sales.getNumber(), sales.getSize(),
                sales.getTotalElements(), sales.getTotalPages());
    }

    /** One sale, the same as its row in the list. 404 when it does not exist. */
    @Transactional(readOnly = true)
    public SaleResponse get(Long id) {
        return saleRepository.findWithDetailsById(id).map(this::responseOf)
                .orElseThrow(() -> new EntityNotFoundException("Sale " + id + " does not exist."));
    }

    @Transactional(readOnly = true)
    public SaleSummaryResponse summary() {
        return summary(KigaliTime.today());
    }

    // Takes today's date as a parameter so tests can use a fixed month.
    SaleSummaryResponse summary(LocalDate today) {
        LocalDate firstDayOfMonth = today.withDayOfMonth(1);
        LocalDate lastDayOfMonth = today.with(TemporalAdjusters.lastDayOfMonth());
        long amount = saleRepository.totalAmountBetween(firstDayOfMonth, lastDayOfMonth);
        BigDecimal kg = saleRepository.totalKgBetween(firstDayOfMonth, lastDayOfMonth);
        BigDecimal averagePricePerKg = kg.signum() == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(amount).divide(kg, 2, RoundingMode.HALF_UP);
        return new SaleSummaryResponse(amount, kg,
                saleRepository.buyerCountBetween(firstDayOfMonth, lastDayOfMonth), averagePricePerKg);
    }

    private SaleOrderResponse toResponse(SaleOrder order) {
        return SaleOrderResponse.from(order, responsesOf(saleRepository.findBySaleOrderIdOrderByIdAsc(order.getId())));
    }

    private SaleResponse responseOf(Sale sale) {
        return SaleResponse.from(sale, saleAllocationRepository.findBySaleIdOrderById(sale.getId()));
    }

    // The batches of all the sales in one query, not one per sale.
    private List<SaleResponse> responsesOf(List<Sale> sales) {
        if (sales.isEmpty()) {
            return List.of();
        }
        Map<Long, List<SaleAllocation>> batches = saleAllocationRepository
                .findBySaleIdInOrderByIdAsc(sales.stream().map(Sale::getId).toList()).stream()
                .collect(Collectors.groupingBy(allocation -> allocation.getSale().getId()));
        return sales.stream()
                .map(sale -> SaleResponse.from(sale, batches.getOrDefault(sale.getId(), List.of())))
                .toList();
    }

    private SaleOrder saveOrder(Buyer buyer, UUID clientUuid, LocalDate saleDate, PaymentMethod method,
                                String reference) {
        SaleOrder order = new SaleOrder();
        order.setOrderCode(ShortCodes.unique("ORD-", saleOrderRepository::existsByOrderCode));
        order.setClientUuid(clientUuid);
        order.setBuyer(buyer);
        order.setSaleDate(saleDate);
        order.setPaymentMethod(method);
        order.setPaymentReference(reference);
        return saleOrderRepository.save(order);
    }

    // The buyer pays in full; MOMO and BANK need a well-formed transaction reference. Checked before any lock.
    private static String checkedReference(PaymentMethod method, String reference) {
        if (method == null) {
            throw new BadRequestException("paymentMethod: is required (CASH, MOMO or BANK)");
        }
        return PaymentReferences.checked(method, reference,
                "A " + method + " buyer payment needs the transaction reference.");
    }

    // Locked first, so two sales with the same reference cannot both find it unused. Called after the client UUID
    // check, so a repeated request gets its sale back instead of a 409. Orders are never reversed, so every order counts.
    private void checkReferenceUnused(PaymentMethod method, String reference) {
        if (!PaymentReferences.mustBeUnique(method)) {
            return;
        }
        saleOrderRepository.lockReference(PaymentReferences.BUYER_PAYMENT_LOCK,
                PaymentReferences.lockKey(method, reference));
        Optional<String> usedBy = saleOrderRepository.findCodesUsingReference(method, reference).stream().findFirst();
        if (usedBy.isPresent()) {
            throw PaymentReferences.alreadyUsed(reference, usedBy.get());
        }
    }

    private Buyer findBuyer(Long buyerId) {
        return buyerRepository.findById(buyerId)
                .orElseThrow(() -> new BadRequestException("Buyer " + buyerId + " does not exist."));
    }

    // Checks what can be checked without the database, before any lock is taken. The grade is added once locked.
    // A sale always takes the oldest kg first, so asking to skip is refused. Empty skip fields ask for nothing.
    private static Wanted checkedWanted(BigDecimal quantityKg, Long pricePerKg, List<Long> skipLotIds,
                                        String skipReason, List<Long> skipDeliveryIds) {
        if (skipLotIds != null && !skipLotIds.isEmpty() || skipReason != null && !skipReason.isBlank()
                || skipDeliveryIds != null && !skipDeliveryIds.isEmpty()) {
            throw new BadRequestException(SalePlanner.NO_SKIPPING);
        }
        return new Wanted(null, quantityKg, pricePerKg);
    }

    private static Wanted withGrade(Wanted wanted, Grade grade) {
        return new Wanted(grade, wanted.quantityKg(), wanted.pricePerKg());
    }

    private Map<Long, Grade> lockGrades(List<Long> gradeIds) {
        Map<Long, Grade> grades = new LinkedHashMap<>();
        for (Long gradeId : gradeIds.stream().sorted().toList()) {
            grades.put(gradeId, gradeRepository.findForUpdateById(gradeId)
                    .orElseThrow(() -> new BadRequestException("Grade " + gradeId + " does not exist.")));
        }
        return grades;
    }

    /**
     * Takes the lots, then the portions and their deliveries, and works out each sale from what is left once nothing
     * can change. The result is the one that is saved. Throws, so nothing is saved, when a grade has too few sellable
     * kg.
     */
    private List<Planned> planUnderLocks(List<Wanted> wanted) {
        long deductionPerKg = settingRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("The settings row is missing."))
                .getDeductionPerKg();

        List<Wanted> byGrade = wanted.stream().sorted(Comparator.comparing(item -> item.grade().getId())).toList();
        Map<Long, List<Long>> lotIdsByGrade = new LinkedHashMap<>();
        Set<Long> lotIds = new TreeSet<>();
        for (Wanted item : byGrade) {
            List<Long> ids = lotPortionRepository.findLotIdsWithSellable(item.grade().getId());
            lotIdsByGrade.put(item.grade().getId(), ids);
            lotIds.addAll(ids);
        }
        // Lots in id order, so two sales never wait for each other's lots in opposite orders.
        for (Long lotId : lotIds) {
            lotRepository.findForUpdateById(lotId).orElseThrow();
        }

        Map<Long, Planned> plannedByGrade = new LinkedHashMap<>();
        for (Wanted item : byGrade) {
            Long gradeId = item.grade().getId();
            List<LotPortion> portions = lotIdsByGrade.get(gradeId).isEmpty() ? List.of()
                    : lotPortionRepository.findSellable(gradeId);
            lockPortionsAndDeliveries(portions);
            SalePlan plan = SalePlanner.plan(portions.stream().map(SellableBatch::from).toList(), item.quantityKg(),
                    item.pricePerKg(), deductionPerKg);
            if (!plan.enough()) {
                BigDecimal waitingKg = batchRepository.gradeRemainingKgWithStatus(gradeId, ConfirmationStatus.PENDING);
                throw new BadRequestException(plan.shortageMessage(item.grade().getName(), waitingKg));
            }
            plannedByGrade.put(gradeId, new Planned(item, plan,
                    portions.stream().collect(Collectors.toMap(LotPortion::getId, Function.identity()))));
        }
        // In the order of the request.
        return wanted.stream().map(item -> plannedByGrade.get(item.grade().getId())).toList();
    }

    // The grade and its lots are already held, so what was read cannot change; these row locks keep the order of locks
    // the same as everywhere else: portions, then their deliveries.
    private void lockPortionsAndDeliveries(List<LotPortion> portions) {
        if (portions.isEmpty()) {
            return;
        }
        lotPortionRepository.lockByIds(portions.stream().map(LotPortion::getId).sorted().toList());
        deliveryRepository.lockByIds(portions.stream().map(portion -> portion.getDelivery().getId())
                .distinct().sorted().toList());
    }

    // Saves the sale, then for each portion used: its sold kg and its delivery's, and the allocation (with the money),
    // then the ledger: the SALE entry and one SALE_CREDIT entry per allocation.
    private Sale saveSale(Planned planned, Buyer buyer, UUID clientUuid, SaleOrder order, LocalDate saleDate,
                          Manager manager) {
        Wanted wanted = planned.wanted();
        SalePlan plan = planned.plan();

        Sale sale = new Sale();
        sale.setClientUuid(clientUuid);
        sale.setSaleCode(ShortCodes.unique("SAL-", saleRepository::existsBySaleCode));
        sale.setSaleOrder(order);
        sale.setGrade(wanted.grade());
        sale.setBuyer(buyer);
        sale.setSaleDate(saleDate);
        sale.setQuantityKg(wanted.quantityKg());
        sale.setPricePerKg(wanted.pricePerKg());
        sale.setTotalAmount(plan.totalAmountRwf());
        sale.setBelowFloorPrice(isBelowFloor(wanted.grade(), wanted.pricePerKg()));
        Sale saved = saleRepository.save(sale);

        List<SaleAllocation> allocations = new ArrayList<>();
        for (SalePlan.Line line : plan.lines()) {
            LotPortion portion = planned.portions().get(line.batch().portionId());
            Delivery batch = portion.getDelivery();
            portion.addSoldKg(line.kg());
            batch.addSoldKg(line.kg());
            SaleAllocation allocation = new SaleAllocation(saved, batch, line.kg(), line.grossRwf(),
                    line.deductionRwf());
            allocation.setLotPortion(portion);
            allocations.add(saleAllocationRepository.save(allocation));
        }

        ledgerService.record(LedgerEntryType.SALE, "SALE", saved.getId(), saved.getQuantityKg(),
                saved.getTotalAmount(),
                "Sale " + saved.getSaleCode() + " of " + wanted.grade().getName() + " to " + buyer.getName(), manager);
        // The reason holds codes only, no names: the member is found through the batch.
        for (SaleAllocation allocation : allocations) {
            ledgerService.record(LedgerEntryType.SALE_CREDIT, "SALE_ALLOCATION", allocation.getId(),
                    allocation.getQuantityKg(), allocation.netRwf(),
                    "Credit for sale " + saved.getSaleCode() + " from " + allocation.getDelivery().getReceiptCode(),
                    manager);
        }
        return saved;
    }

    // A full lot is sold when nothing is left in it; so is an older lot, whether or not it was closed. A lot still
    // being filled stays FILLING: it can sell kg and keep filling.
    private void updateLotStatuses(List<Planned> planned) {
        List<Lot> touched = planned.stream()
                .flatMap(item -> item.plan().lines().stream()
                        .map(line -> item.portions().get(line.batch().portionId()).getLot()))
                .distinct()
                .toList();
        for (Lot lot : touched) {
            boolean canBeSold = lot.getStatus() == LotStatus.FULL || lot.getStatus() == LotStatus.OPEN
                    || lot.getStatus() == LotStatus.CLOSED;
            if (canBeSold && lotPortionRepository.remainingKg(lot.getId()).signum() == 0) {
                lot.setStatus(LotStatus.SOLD);
                if (lot.getCloseDate() == null) {
                    lot.setCloseDate(KigaliTime.today());
                }
            }
        }
    }

    // Only a warning for the manager; a grade without a floor price is never below it.
    private static boolean isBelowFloor(Grade grade, long pricePerKg) {
        Long floorPricePerKg = grade.getFloorPricePerKg();
        return floorPricePerKg != null && pricePerKg < floorPricePerKg;
    }

    // The sale's batches that satisfy a condition, for an EXISTS test on the sale.
    private static Subquery<Long> batchesOf(CriteriaQuery<?> query, CriteriaBuilder cb, Root<Sale> sale,
                                            Function<Root<SaleAllocation>, Predicate> condition) {
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<SaleAllocation> allocation = subquery.from(SaleAllocation.class);
        return subquery.select(allocation.<Long>get("id"))
                .where(cb.equal(allocation.get("sale").get("id"), sale.get("id")), condition.apply(allocation));
    }

    // The test holds for the lot an allocation's kg came from: its portion's, or for an allocation from before
    // fixed-size lots (no portion), its delivery's own lot.
    private static Predicate lotMatches(CriteriaBuilder cb, Root<SaleAllocation> allocation,
                                        Function<Join<?, ?>, Predicate> test) {
        Join<?, ?> portionLot = allocation.join("lotPortion", JoinType.LEFT).join("lot", JoinType.LEFT);
        Join<?, ?> ownLot = allocation.join("delivery").join("lot", JoinType.LEFT);
        return cb.or(test.apply(portionLot), cb.and(cb.isNull(allocation.get("lotPortion")), test.apply(ownLot)));
    }

    private static Specification<Sale> matching(String search, Long lotId, Long buyerId, Long gradeId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (lotId != null) {
                // A sale made from one lot, or a sale by grade that took kg from the lot's batches.
                predicates.add(cb.or(cb.equal(root.get("lot").get("id"), lotId),
                        cb.exists(batchesOf(query, cb, root,
                                a -> lotMatches(cb, a, lot -> cb.equal(lot.get("id"), lotId))))));
            }
            if (buyerId != null) {
                predicates.add(cb.equal(root.get("buyer").get("id"), buyerId));
            }
            if (gradeId != null) {
                predicates.add(cb.equal(root.get("grade").get("id"), gradeId));
            }
            if (search != null && !search.isBlank()) {
                String term = search.trim();
                String pattern = "%" + term.toLowerCase(Locale.ROOT) + "%";
                List<Predicate> matches = new ArrayList<>();
                matches.add(cb.like(cb.lower(root.get("buyer").get("name")), pattern));
                // A sale by grade has no lot of its own, so the lot code is also looked for in its batches.
                matches.add(cb.like(cb.lower(root.join("lot", JoinType.LEFT).get("lotCode")), pattern));
                matches.add(cb.exists(batchesOf(query, cb, root,
                        a -> lotMatches(cb, a, lot -> cb.like(cb.lower(lot.get("lotCode")), pattern)))));
                matches.add(cb.like(cb.lower(root.get("saleCode")), pattern));
                // A number on its own can also be the sale's id.
                if (term.matches("\\d{1,18}")) {
                    matches.add(cb.equal(root.get("id"), Long.valueOf(term)));
                }
                predicates.add(cb.or(matches.toArray(Predicate[]::new)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
