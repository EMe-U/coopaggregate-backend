package com.coopaggregate.ledger;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
import com.coopaggregate.loss.Loss;
import com.coopaggregate.payment.ProducePayment;
import com.coopaggregate.sale.Sale;
import com.coopaggregate.sale.SaleOrder;
import com.coopaggregate.share.LotShare;

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

    public LedgerQueryService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<LedgerEntryResponse> list(LedgerFilter filter, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "id"));
        Page<LedgerEntry> entries = ledgerEntryRepository.findAll(matching(filter), pageRequest);

        List<Long> entryIds = entries.map(LedgerEntry::getId).toList();
        Map<Long, Long> reversalIdByEntryId = ledgerEntryRepository.findByReversesIdIn(entryIds).stream()
                .collect(Collectors.toMap(reversal -> reversal.getReverses().getId(), LedgerEntry::getId));

        return PageResponse.from(entries.map(
                entry -> LedgerEntryResponse.from(entry, reversalIdByEntryId.get(entry.getId()))));
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
