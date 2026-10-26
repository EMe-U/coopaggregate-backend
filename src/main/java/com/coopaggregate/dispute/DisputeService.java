package com.coopaggregate.dispute;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.common.PageResponse;
import com.coopaggregate.delivery.ConfirmationStatus;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.delivery.DeliveryRepository;
import com.coopaggregate.error.BadRequestException;

import jakarta.persistence.criteria.Predicate;

/**
 * The disputes page, read from the dispute fields on each delivery. Settling stays on the delivery endpoints:
 * resolve-dispute, correct or reverse.
 */
@Service
public class DisputeService {

    private static final int MAX_PAGE_SIZE = 100;

    private final DeliveryRepository deliveryRepository;

    public DisputeService(DeliveryRepository deliveryRepository) {
        this.deliveryRepository = deliveryRepository;
    }

    /** status is "open" (default) or "settled". The response is built here, inside the transaction. */
    @Transactional(readOnly = true)
    public DisputesResponse list(String status, String search, int page, int size) {
        boolean open = isOpen(status);
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "disputedAt", "id"));
        Page<Delivery> deliveries = deliveryRepository.findAll(matching(open, search), pageRequest);

        // Which reversed deliveries were corrected, looked up for the whole page at once.
        Map<Long, Long> correctedByOriginalId = deliveryRepository
                .findByCorrectsIdIn(deliveries.getContent().stream().map(Delivery::getId).toList()).stream()
                .collect(Collectors.toMap(corrected -> corrected.getCorrects().getId(), Delivery::getId));
        Instant now = Instant.now();

        return new DisputesResponse(
                deliveryRepository.countByConfirmationStatusAndReversedAtIsNull(ConfirmationStatus.DISPUTED),
                deliveryRepository.sumOpenDisputedKg(),
                deliveryRepository.countSettledDisputes(),
                PageResponse.from(deliveries.map(delivery ->
                        DisputeItem.from(delivery, correctedByOriginalId.get(delivery.getId()), now))));
    }

    private static boolean isOpen(String status) {
        if (status == null || status.isBlank() || status.trim().equalsIgnoreCase("open")) {
            return true;
        }
        if (status.trim().equalsIgnoreCase("settled")) {
            return false;
        }
        throw new BadRequestException("status must be open or settled.");
    }

    // Open: still DISPUTED and not reversed. Settled: was disputed, and is no longer DISPUTED or was reversed.
    static Specification<Delivery> matching(boolean open, String search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (open) {
                predicates.add(cb.equal(root.get("confirmationStatus"), ConfirmationStatus.DISPUTED));
                predicates.add(cb.isNull(root.get("reversedAt")));
            } else {
                predicates.add(cb.isNotNull(root.get("disputedAt")));
                predicates.add(cb.or(
                        cb.notEqual(root.get("confirmationStatus"), ConfirmationStatus.DISPUTED),
                        cb.isNotNull(root.get("reversedAt"))));
            }
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("receiptCode")), like),
                        cb.like(cb.lower(root.get("member").get("fullName")), like),
                        cb.like(cb.lower(root.get("member").get("memberCode")), like)));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
