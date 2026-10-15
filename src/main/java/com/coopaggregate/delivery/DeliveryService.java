package com.coopaggregate.delivery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.common.PageResponse;
import com.coopaggregate.common.ShortCodes;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;
import com.coopaggregate.member.MemberStatus;
import com.coopaggregate.setting.SettingRepository;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;

@Service
public class DeliveryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Duration MAX_CLOCK_AHEAD = Duration.ofMinutes(5);
    private static final Duration MAX_SYNC_DELAY = Duration.ofDays(7);

    private final DeliveryRepository deliveryRepository;
    private final MemberRepository memberRepository;
    private final GradeRepository gradeRepository;
    private final SettingRepository settingRepository;
    private final LotService lotService;
    private final LedgerService ledgerService;

    public DeliveryService(DeliveryRepository deliveryRepository, MemberRepository memberRepository,
                           GradeRepository gradeRepository, SettingRepository settingRepository,
                           LotService lotService, LedgerService ledgerService) {
        this.deliveryRepository = deliveryRepository;
        this.memberRepository = memberRepository;
        this.gradeRepository = gradeRepository;
        this.settingRepository = settingRepository;
        this.lotService = lotService;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public RecordedDelivery record(DeliveryRequest request, Manager manager) {
        // Lock the grade before looking for the client UUID: if the same offline delivery is sent twice at
        // once, the second request waits here and then finds the first one instead of hitting the unique key.
        Grade grade = gradeRepository.findForUpdateById(request.gradeId())
                .orElseThrow(() -> new BadRequestException("Grade " + request.gradeId() + " does not exist."));

        // Checked before the member, so a re-sent delivery is still accepted after the member is deactivated.
        Optional<Delivery> existing = deliveryRepository.findByClientUuid(request.clientUuid());
        if (existing.isPresent()) {
            return new RecordedDelivery(DeliveryResponse.from(existing.get()), false);
        }

        Instant deliveredAt = request.deliveredAt() != null ? request.deliveredAt() : Instant.now();
        checkDeliveryTime(deliveredAt);
        Member member = activeMember(request.memberId());

        Delivery delivery = new Delivery();
        delivery.setClientUuid(request.clientUuid());
        delivery.setMember(member);
        // The lot is chosen when the delivery reaches the server, not from deliveredAt: an offline delivery
        // goes to the lot that is open for its grade at sync time, or opens a new one if that lot was closed
        // in the meantime. A closed lot may already be on sale, so its kilograms must not change.
        delivery.setLot(lotService.findOrOpenLot(grade));
        delivery.setDeliveredAt(deliveredAt);
        delivery.setQuantityKg(request.quantityKg());
        delivery.setDeductionAmount(deductionFor(request.quantityKg()));
        delivery.setReceiptCode(ShortCodes.unique("RCT-", deliveryRepository::existsByReceiptCode));
        Delivery saved = deliveryRepository.save(delivery);

        // The reason is hashed and can never change, so it holds only the receipt code. The member is found
        // through the delivery; a member number in the text would go stale if numbers were ever renamed.
        ledgerService.record(LedgerEntryType.DELIVERY, "DELIVERY", saved.getId(),
                saved.getQuantityKg(), saved.getDeductionAmount(), "Delivery " + saved.getReceiptCode(), manager);
        return new RecordedDelivery(DeliveryResponse.from(saved), true);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeliveryResponse> list(Long memberId, Long lotId, LocalDate from, LocalDate to,
                                               boolean includeReversed, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "deliveredAt", "id"));
        return PageResponse.from(deliveryRepository.findAll(matching(memberId, lotId, from, to, includeReversed),
                pageRequest)
                .map(DeliveryResponse::from));
    }

    @Transactional(readOnly = true)
    public DeliveryResponse get(Long id) {
        return deliveryRepository.findById(id)
                .map(DeliveryResponse::from)
                .orElseThrow(() -> new EntityNotFoundException("Delivery " + id + " does not exist."));
    }

    // Offline deliveries can arrive hours later, but a time far in the future or the past means a wrong
    // device clock or a mistake, and would put the delivery in the wrong day or week.
    private static void checkDeliveryTime(Instant deliveredAt) {
        Instant now = Instant.now();
        if (deliveredAt.isAfter(now.plus(MAX_CLOCK_AHEAD))) {
            throw new BadRequestException(
                    "deliveredAt is in the future. Check the date and time on the device.");
        }
        if (deliveredAt.isBefore(now.minus(MAX_SYNC_DELAY))) {
            throw new BadRequestException(
                    "deliveredAt is more than 7 days ago. Offline deliveries must be synced within 7 days.");
        }
    }

    private Member activeMember(Long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new BadRequestException("Member " + memberId + " does not exist."));
        if (member.getStatus() != MemberStatus.ACTIVE) {
            throw new BadRequestException("Member " + member.getMemberCode()
                    + " is inactive. Activate the member before recording a delivery.");
        }
        return member;
    }

    // The deduction is stored in whole RWF, so fractions are rounded (half up).
    private long deductionFor(BigDecimal quantityKg) {
        long deductionPerKg = settingRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("The settings row is missing."))
                .getDeductionPerKg();
        return quantityKg.multiply(BigDecimal.valueOf(deductionPerKg))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }


    // from and to are whole days in Kigali time; to is inclusive.
    static Specification<Delivery> matching(Long memberId, Long lotId, LocalDate from, LocalDate to,
                                            boolean includeReversed) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (!includeReversed) {
                predicates.add(cb.isNull(root.get("reversedAt")));
            }
            if (memberId != null) {
                predicates.add(cb.equal(root.get("member").get("id"), memberId));
            }
            if (lotId != null) {
                predicates.add(cb.equal(root.get("lot").get("id"), lotId));
            }
            if (from != null) {
                Instant start = from.atStartOfDay(KigaliTime.ZONE).toInstant();
                predicates.add(cb.greaterThanOrEqualTo(root.get("deliveredAt"), start));
            }
            if (to != null) {
                Instant end = to.plusDays(1).atStartOfDay(KigaliTime.ZONE).toInstant();
                predicates.add(cb.lessThan(root.get("deliveredAt"), end));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
