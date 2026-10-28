package com.coopaggregate.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.batch.BatchAllocator;
import com.coopaggregate.batch.SaleAllocation;
import com.coopaggregate.batch.SaleAllocationRepository;
import com.coopaggregate.buyer.Buyer;
import com.coopaggregate.buyer.BuyerRepository;
import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.common.PageResponse;
import com.coopaggregate.common.ShortCodes;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

@Service
public class SaleService {

    private static final int MAX_PAGE_SIZE = 100;

    private final SaleRepository saleRepository;
    private final SaleOrderRepository saleOrderRepository;
    private final LotRepository lotRepository;
    private final BuyerRepository buyerRepository;
    private final LotService lotService;
    private final LedgerService ledgerService;
    private final BatchAllocator batchAllocator;
    private final SaleAllocationRepository saleAllocationRepository;

    public SaleService(SaleRepository saleRepository, SaleOrderRepository saleOrderRepository,
                       LotRepository lotRepository, BuyerRepository buyerRepository, LotService lotService,
                       LedgerService ledgerService, BatchAllocator batchAllocator,
                       SaleAllocationRepository saleAllocationRepository) {
        this.saleRepository = saleRepository;
        this.saleOrderRepository = saleOrderRepository;
        this.lotRepository = lotRepository;
        this.buyerRepository = buyerRepository;
        this.lotService = lotService;
        this.ledgerService = ledgerService;
        this.batchAllocator = batchAllocator;
        this.saleAllocationRepository = saleAllocationRepository;
    }

    @Transactional
    public RecordedSale record(SaleRequest request, Manager manager) {
        // The row lock makes sales and losses of the same lot wait for each other, so two sales cannot
        // both pass the remaining kg check. It also makes a repeated client UUID wait for the first request.
        Lot lot = lotRepository.findForUpdateById(request.lotId())
                .orElseThrow(() -> new BadRequestException("Lot " + request.lotId() + " does not exist."));

        // Checked before the lot status, so a re-sent sale is still answered after the lot became SOLD.
        Optional<Sale> existing = saleRepository.findByClientUuid(request.clientUuid());
        if (existing.isPresent()) {
            return new RecordedSale(responseOf(existing.get()), false);
        }

        BigDecimal remainingKg = checkSellable(lot, request.quantityKg());
        Buyer buyer = findBuyer(request.buyerId());
        LocalDate saleDate = request.soldAt() != null ? request.soldAt() : KigaliTime.today();
        Sale saved = saveSale(lot, buyer, request.clientUuid(), null, saleDate, request.quantityKg(),
                request.pricePerKg(), remainingKg, manager);
        return new RecordedSale(responseOf(saved), true);
    }

    @Transactional
    public RecordedSaleOrder recordOrder(SaleOrderRequest request, Manager manager) {
        List<Long> lotIds = request.items().stream().map(SaleOrderRequest.Item::lotId).toList();
        if (new HashSet<>(lotIds).size() != lotIds.size()) {
            throw new BadRequestException("Each lot can appear only once in an order.");
        }

        // Locked in id order, so two orders that share lots always lock them in the same order and cannot
        // deadlock. The locks also make a repeated client UUID wait for the first request.
        Map<Long, Lot> lots = new HashMap<>();
        for (Long lotId : lotIds.stream().sorted().toList()) {
            lots.put(lotId, lotRepository.findForUpdateById(lotId)
                    .orElseThrow(() -> new BadRequestException("Lot " + lotId + " does not exist.")));
        }

        Optional<SaleOrder> existing = saleOrderRepository.findByClientUuid(request.clientUuid());
        if (existing.isPresent()) {
            return new RecordedSaleOrder(toResponse(existing.get()), false);
        }

        // Every item is checked before anything is saved, so a problem in any item saves nothing.
        Buyer buyer = findBuyer(request.buyerId());
        Map<Long, BigDecimal> remainingByLot = new HashMap<>();
        for (SaleOrderRequest.Item item : request.items()) {
            remainingByLot.put(item.lotId(), checkSellable(lots.get(item.lotId()), item.quantityKg()));
        }

        SaleOrder order = new SaleOrder();
        order.setOrderCode(ShortCodes.unique("ORD-", saleOrderRepository::existsByOrderCode));
        order.setClientUuid(request.clientUuid());
        order.setBuyer(buyer);
        order.setSaleDate(request.soldAt() != null ? request.soldAt() : KigaliTime.today());
        SaleOrder savedOrder = saleOrderRepository.save(order);

        List<Sale> savedSales = new ArrayList<>();
        for (SaleOrderRequest.Item item : request.items()) {
            // The order holds the client UUID, so each sale row gets a random one of its own.
            Sale saved = saveSale(lots.get(item.lotId()), buyer, UUID.randomUUID(), savedOrder,
                    savedOrder.getSaleDate(), item.quantityKg(), item.pricePerKg(),
                    remainingByLot.get(item.lotId()), manager);
            savedSales.add(saved);
        }
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

    private Buyer findBuyer(Long buyerId) {
        return buyerRepository.findById(buyerId)
                .orElseThrow(() -> new BadRequestException("Buyer " + buyerId + " does not exist."));
    }

    // Returns the lot's remaining kg after checking the lot can be sold and holds enough.
    private BigDecimal checkSellable(Lot lot, BigDecimal quantityKg) {
        if (lot.getStatus() != LotStatus.CLOSED) {
            throw new BadRequestException("Lot " + lot.getLotCode() + " is " + lot.getStatus()
                    + ". Only a closed lot can be sold.");
        }
        BigDecimal remainingKg = lotService.quantitiesOf(lot).remainingKg();
        if (quantityKg.compareTo(remainingKg) > 0) {
            throw new BadRequestException("Only " + remainingKg.toPlainString() + " kg remain in lot "
                    + lot.getLotCode() + ".");
        }
        return remainingKg;
    }

    private Sale saveSale(Lot lot, Buyer buyer, UUID clientUuid, SaleOrder order, LocalDate saleDate,
                          BigDecimal quantityKg, long pricePerKg, BigDecimal remainingKg, Manager manager) {
        Sale sale = new Sale();
        sale.setClientUuid(clientUuid);
        sale.setSaleCode(ShortCodes.unique("SAL-", saleRepository::existsBySaleCode));
        sale.setSaleOrder(order);
        sale.setGrade(lot.getGrade());
        sale.setLot(lot);
        sale.setBuyer(buyer);
        sale.setSaleDate(saleDate);
        sale.setQuantityKg(quantityKg);
        sale.setPricePerKg(pricePerKg);
        sale.setTotalAmount(totalAmount(quantityKg, pricePerKg));
        sale.setBelowFloorPrice(isBelowFloor(lot, pricePerKg));
        Sale saved = saleRepository.save(sale);
        batchAllocator.allocateSale(saved);
        ledgerService.record(LedgerEntryType.SALE, "SALE", saved.getId(), saved.getQuantityKg(),
                saved.getTotalAmount(),
                "Sale " + saved.getSaleCode() + " from " + lot.getLotCode() + " to " + buyer.getName(), manager);

        if (quantityKg.compareTo(remainingKg) == 0) {
            lot.setStatus(LotStatus.SOLD);
        }
        return saved;
    }

    // Amounts are stored in whole RWF, so fractions are rounded (half up).
    private static long totalAmount(BigDecimal quantityKg, long pricePerKg) {
        return quantityKg.multiply(BigDecimal.valueOf(pricePerKg))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    // Only a warning for the manager; a grade without a floor price is never below it.
    private static boolean isBelowFloor(Lot lot, long pricePerKg) {
        Long floorPricePerKg = lot.getGrade().getFloorPricePerKg();
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

    private static Specification<Sale> matching(String search, Long lotId, Long buyerId, Long gradeId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (lotId != null) {
                // A sale made from one lot, or a sale by grade that took kg from the lot's batches.
                predicates.add(cb.or(cb.equal(root.get("lot").get("id"), lotId),
                        cb.exists(batchesOf(query, cb, root,
                                a -> cb.equal(a.get("delivery").get("lot").get("id"), lotId)))));
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
                        a -> cb.like(cb.lower(a.get("delivery").get("lot").get("lotCode")), pattern))));
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
