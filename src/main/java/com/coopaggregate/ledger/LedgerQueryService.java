package com.coopaggregate.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.loss.Loss;
import com.coopaggregate.loss.LossRepository;
import com.coopaggregate.payment.ProducePayment;
import com.coopaggregate.payment.ProducePaymentRepository;
import com.coopaggregate.sale.Sale;
import com.coopaggregate.sale.SaleOrder;
import com.coopaggregate.sale.SaleRepository;
import com.coopaggregate.share.LotShare;
import com.coopaggregate.share.LotShareRepository;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

/** Read side of the ledger: listing and searching entries for the Ledger screen. */
@Service
public class LedgerQueryService {

    private static final int MAX_PAGE_SIZE = 100;

    private final LedgerEntryRepository ledgerEntryRepository;
    private final DeliveryRepository deliveryRepository;
    private final LossRepository lossRepository;
    private final SaleRepository saleRepository;
    private final LotShareRepository lotShareRepository;
    private final ProducePaymentRepository paymentRepository;

    public LedgerQueryService(LedgerEntryRepository ledgerEntryRepository, DeliveryRepository deliveryRepository,
                              LossRepository lossRepository, SaleRepository saleRepository,
                              LotShareRepository lotShareRepository, ProducePaymentRepository paymentRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.deliveryRepository = deliveryRepository;
        this.lossRepository = lossRepository;
        this.saleRepository = saleRepository;
        this.lotShareRepository = lotShareRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryResponse> list(LedgerFilter filter, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "id"));
        Page<LedgerEntry> entries = ledgerEntryRepository.findAll(matching(filter), pageRequest);
        List<LedgerEntryResponse> responses = toResponses(entries.getContent());
        return new PageResponse<>(responses, entries.getNumber(), entries.getSize(), entries.getTotalElements(),
                entries.getTotalPages());
    }

    @Transactional(readOnly = true)
    public LedgerSummaryResponse summary() {
        LocalDate today = KigaliTime.today();
        // Entries are appended in order, so the highest id is also the latest entry.
        Instant lastEntryAt = ledgerEntryRepository.findTopByOrderByIdDesc()
                .map(LedgerEntry::getEntryDate)
                .orElse(null);
        return new LedgerSummaryResponse(ledgerEntryRepository.count(), lastEntryAt,
                ledgerEntryRepository.countBetween(startOfDay(today), startOfDay(today.plusDays(1))));
    }

    @Transactional(readOnly = true)
    public LedgerEntryResponse toResponse(LedgerEntry entry) {
        return toResponses(List.of(entry)).get(0);
    }

    // One lookup per kind of business record on the page, not one per entry.
    List<LedgerEntryResponse> toResponses(List<LedgerEntry> entries) {
        Map<Long, Long> reversalIdByEntryId = ledgerEntryRepository
                .findByReversesIdIn(entries.stream().map(LedgerEntry::getId).toList()).stream()
                .collect(Collectors.toMap(reversal -> reversal.getReverses().getId(), LedgerEntry::getId));
        Map<String, Map<Long, LedgerReference>> references = references(entries);
        Map<Long, LedgerEntryType> reversedTypes = reversedTypes(entries);

        return entries.stream().map(entry -> {
            LedgerReference reference = references
                    .getOrDefault(entry.getRelatedEntityType(), Map.of())
                    .getOrDefault(entry.getRelatedEntityId(), LedgerReference.NONE);
            return new LedgerEntryResponse(
                    entry.getId(),
                    entry.getEntryDate(),
                    entry.getEntryType(),
                    entry.getReverses() == null ? null : reversedTypes.get(entry.getReverses().getId()),
                    describe(entry),
                    reference.referenceCode(),
                    reference.memberId(),
                    reference.memberName(),
                    reference.buyerName(),
                    reference.lotId(),
                    reference.lotCode(),
                    entry.getQuantityKg(),
                    entry.getAmount(),
                    entry.getCurrentHash().substring(0, 10),
                    entry.getCurrentHash(),
                    entry.getPreviousHash(),
                    entry.getReverses() == null ? null : entry.getReverses().getId(),
                    reversalIdByEntryId.get(entry.getId()));
        }).toList();
    }

    // The entries that reversals cancel, loaded in one query instead of one per reversal.
    private Map<Long, LedgerEntryType> reversedTypes(List<LedgerEntry> entries) {
        List<Long> reversedIds = entries.stream()
                .filter(entry -> entry.getReverses() != null)
                .map(entry -> entry.getReverses().getId())
                .toList();
        if (reversedIds.isEmpty()) {
            return Map.of();
        }
        return ledgerEntryRepository.findAllById(reversedIds).stream()
                .collect(Collectors.toMap(LedgerEntry::getId, LedgerEntry::getEntryType));
    }

    private Map<String, Map<Long, LedgerReference>> references(List<LedgerEntry> entries) {
        Map<String, Set<Long>> idsByType = new HashMap<>();
        for (LedgerEntry entry : entries) {
            if (entry.getRelatedEntityType() != null && entry.getRelatedEntityId() != null) {
                idsByType.computeIfAbsent(entry.getRelatedEntityType(), type -> new HashSet<>())
                        .add(entry.getRelatedEntityId());
            }
        }

        Map<String, Map<Long, LedgerReference>> references = new HashMap<>();
        idsByType.forEach((type, ids) -> references.put(type, switch (type) {
            case "DELIVERY" -> deliveryRepository.findByIdIn(ids).stream().collect(Collectors.toMap(
                    Delivery::getId, d -> new LedgerReference(d.getReceiptCode(), d.getMember().getId(),
                            d.getMember().getFullName(), d.getLot().getId(), d.getLot().getLotCode(), null)));
            case "LOSS" -> lossRepository.findByIdIn(ids).stream().collect(Collectors.toMap(
                    Loss::getId, loss -> new LedgerReference(null, null, null, loss.getLot().getId(),
                            loss.getLot().getLotCode(), null)));
            case "SALE" -> saleRepository.findByIdIn(ids).stream().collect(Collectors.toMap(
                    Sale::getId, s -> new LedgerReference(s.getSaleCode(), null, null, s.getLot().getId(),
                            s.getLot().getLotCode(), s.getBuyer().getName())));
            case "LOT_SHARE" -> lotShareRepository.findByIdIn(ids).stream().collect(Collectors.toMap(
                    LotShare::getId, share -> new LedgerReference(null, share.getMember().getId(),
                            share.getMember().getFullName(), share.getLot().getId(), share.getLot().getLotCode(),
                            null)));
            case "PAYMENT" -> paymentRepository.findByIdIn(ids).stream().collect(Collectors.toMap(
                    ProducePayment::getId, p -> new LedgerReference(p.getPaymentCode(), p.getMember().getId(),
                            p.getMember().getFullName(), null, null, null)));
            default -> Map.of();
        }));
        return references;
    }

    private static String describe(LedgerEntry entry) {
        if (entry.getEntryType() == LedgerEntryType.REVERSAL && entry.getReverses() != null) {
            return "Reversal of entry #" + entry.getReverses().getId()
                    + (entry.getReason() != null ? ": " + entry.getReason() : "");
        }
        return entry.getReason() != null ? entry.getReason() : entry.getEntryType().name();
    }

    // A ledger entry only points to its business record (related type and id), so the member, lot and
    // codes are found through subqueries on that record. A reversal keeps the type and id of the entry
    // it reverses, so it is found by the same filters.
    static Specification<LedgerEntry> matching(LedgerFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.type() != null) {
                predicates.add(cb.equal(root.get("entryType"), filter.type()));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("entryDate"), startOfDay(filter.from())));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThan(root.get("entryDate"), startOfDay(filter.to().plusDays(1))));
            }
            if (filter.memberId() != null) {
                Long memberId = filter.memberId();
                predicates.add(cb.or(
                        related(root, cb, "DELIVERY", idsOf(query, Delivery.class,
                                d -> cb.equal(d.get("member").get("id"), memberId))),
                        related(root, cb, "LOT_SHARE", idsOf(query, LotShare.class,
                                share -> cb.equal(share.get("member").get("id"), memberId))),
                        related(root, cb, "PAYMENT", idsOf(query, ProducePayment.class,
                                p -> cb.equal(p.get("member").get("id"), memberId)))));
            }
            if (filter.lotId() != null) {
                Long lotId = filter.lotId();
                predicates.add(cb.or(
                        related(root, cb, "DELIVERY", idsOf(query, Delivery.class,
                                d -> cb.equal(d.get("lot").get("id"), lotId))),
                        related(root, cb, "LOSS", idsOf(query, Loss.class,
                                loss -> cb.equal(loss.get("lot").get("id"), lotId))),
                        related(root, cb, "SALE", idsOf(query, Sale.class,
                                s -> cb.equal(s.get("lot").get("id"), lotId))),
                        related(root, cb, "LOT_SHARE", idsOf(query, LotShare.class,
                                share -> cb.equal(share.get("lot").get("id"), lotId)))));
            }
            if (filter.search() != null && !filter.search().isBlank()) {
                predicates.add(searchPredicate(root, query, cb,
                        "%" + filter.search().trim().toLowerCase(Locale.ROOT) + "%"));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    // Reference codes (RCT-, SAL-, ORD-, PAY-) and member names.
    private static Predicate searchPredicate(Root<LedgerEntry> root, CriteriaQuery<?> query, CriteriaBuilder cb,
                                             String pattern) {
        return cb.or(
                related(root, cb, "DELIVERY", idsOf(query, Delivery.class, d -> cb.or(
                        cb.like(cb.lower(d.get("receiptCode")), pattern),
                        cb.like(cb.lower(d.get("member").get("fullName")), pattern)))),
                related(root, cb, "SALE", idsOf(query, Sale.class, s -> {
                    Join<Sale, SaleOrder> order = s.join("saleOrder", JoinType.LEFT);
                    return cb.or(
                            cb.like(cb.lower(s.get("saleCode")), pattern),
                            cb.like(cb.lower(order.get("orderCode")), pattern));
                })),
                related(root, cb, "LOT_SHARE", idsOf(query, LotShare.class,
                        share -> cb.like(cb.lower(share.get("member").get("fullName")), pattern))),
                related(root, cb, "PAYMENT", idsOf(query, ProducePayment.class, p -> cb.or(
                        cb.like(cb.lower(p.get("paymentCode")), pattern),
                        cb.like(cb.lower(p.get("member").get("fullName")), pattern)))));
    }

    private static Predicate related(Root<LedgerEntry> root, CriteriaBuilder cb, String relatedType,
                                     Subquery<Long> relatedIds) {
        return cb.and(cb.equal(root.get("relatedEntityType"), relatedType),
                root.get("relatedEntityId").in(relatedIds));
    }

    private static <T> Subquery<Long> idsOf(CriteriaQuery<?> query, Class<T> type,
                                           Function<Root<T>, Predicate> condition) {
        Subquery<Long> subquery = query.subquery(Long.class);
        Root<T> from = subquery.from(type);
        return subquery.select(from.<Long>get("id")).where(condition.apply(from));
    }

    private static Instant startOfDay(LocalDate day) {
        return day.atStartOfDay(KigaliTime.ZONE).toInstant();
    }
}
