package com.coopaggregate.delivery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
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

    // No 0/O or 1/I/L, so a receipt code read aloud or from a small screen is not misread.
    private static final String RECEIPT_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int RECEIPT_CODE_ATTEMPTS = 10;
    private static final int MAX_PAGE_SIZE = 100;

    private final DeliveryRepository deliveryRepository;
    private final MemberRepository memberRepository;
    private final GradeRepository gradeRepository;
    private final SettingRepository settingRepository;
    private final LotService lotService;
    private final LedgerService ledgerService;
    private final SecureRandom random = new SecureRandom();

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

        Member member = activeMember(request.memberId());

        Delivery delivery = new Delivery();
        delivery.setClientUuid(request.clientUuid());
        delivery.setMember(member);
        delivery.setLot(lotService.findOrOpenLot(grade));
        delivery.setDeliveredAt(request.deliveredAt() != null ? request.deliveredAt() : Instant.now());
        delivery.setQuantityKg(request.quantityKg());
        delivery.setDeductionAmount(deductionFor(request.quantityKg()));
        delivery.setReceiptCode(newReceiptCode());
        Delivery saved = deliveryRepository.save(delivery);

        ledgerService.record(LedgerEntryType.DELIVERY, "DELIVERY", saved.getId(),
                saved.getQuantityKg(), saved.getDeductionAmount(),
                "Delivery " + saved.getReceiptCode() + " from " + member.getMemberCode(), manager);
        return new RecordedDelivery(DeliveryResponse.from(saved), true);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeliveryResponse> list(Long memberId, Long lotId, LocalDate from, LocalDate to,
                                               int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "deliveredAt", "id"));
        return PageResponse.from(deliveryRepository.findAll(matching(memberId, lotId, from, to), pageRequest)
                .map(DeliveryResponse::from));
    }

    @Transactional(readOnly = true)
    public DeliveryResponse get(Long id) {
        return deliveryRepository.findById(id)
                .map(DeliveryResponse::from)
                .orElseThrow(() -> new EntityNotFoundException("Delivery " + id + " does not exist."));
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

    private String newReceiptCode() {
        for (int attempt = 0; attempt < RECEIPT_CODE_ATTEMPTS; attempt++) {
            StringBuilder code = new StringBuilder("RCT-");
            for (int i = 0; i < 4; i++) {
                code.append(RECEIPT_ALPHABET.charAt(random.nextInt(RECEIPT_ALPHABET.length())));
            }
            if (!deliveryRepository.existsByReceiptCode(code.toString())) {
                return code.toString();
            }
        }
        throw new IllegalStateException("Could not create a unique receipt code. Please try again.");
    }

    // from and to are whole days in Kigali time; to is inclusive.
    private static Specification<Delivery> matching(Long memberId, Long lotId, LocalDate from, LocalDate to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
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
