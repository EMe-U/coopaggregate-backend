package com.coopaggregate.delivery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import com.coopaggregate.ledger.LedgerEntry;
import com.coopaggregate.ledger.LedgerEntryRepository;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberPinService;
import com.coopaggregate.member.MemberRepository;
import com.coopaggregate.member.MemberStatus;
import com.coopaggregate.member.PinCheck;
import com.coopaggregate.sale.SaleRepository;
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
    private final LotRepository lotRepository;
    private final SaleRepository saleRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final MemberPinService memberPinService;

    public DeliveryService(DeliveryRepository deliveryRepository, MemberRepository memberRepository,
                           GradeRepository gradeRepository, SettingRepository settingRepository,
                           LotService lotService, LedgerService ledgerService, LotRepository lotRepository,
                           SaleRepository saleRepository, LedgerEntryRepository ledgerEntryRepository,
                           MemberPinService memberPinService) {
        this.deliveryRepository = deliveryRepository;
        this.memberRepository = memberRepository;
        this.gradeRepository = gradeRepository;
        this.settingRepository = settingRepository;
        this.lotService = lotService;
        this.ledgerService = ledgerService;
        this.lotRepository = lotRepository;
        this.saleRepository = saleRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.memberPinService = memberPinService;
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
        // With a PIN the member row is locked, so failed attempts of deliveries syncing at once are all counted.
        Member member = activeMember(request.memberId(), request.confirmationPin() != null);

        Delivery delivery = new Delivery();
        delivery.setClientUuid(request.clientUuid());
        delivery.setMember(member);
        // A wrong or locked PIN never loses the delivery: it is saved as PENDING and confirmed later.
        PinCheck pinCheck = memberPinService.check(member, request.confirmationPin());
        if (pinCheck == PinCheck.CORRECT) {
            delivery.confirm(ConfirmationMethod.PIN, Instant.now());
        }
        delivery.setPinMismatch(pinCheck == PinCheck.WRONG);
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
                .map(this::toResponse));
    }

    @Transactional(readOnly = true)
    public DeliveryResponse get(Long id) {
        return toResponse(findDelivery(id));
    }

    /**
     * Cancels a delivery: it gets a REVERSAL ledger entry and is no longer counted anywhere. Only allowed while
     * its lot has no sales, because sold potatoes and shared money already depend on the lot's kilograms.
     */
    @Transactional
    public Delivery reverse(Long deliveryId, String reason, Manager manager) {
        Lot lot = lockLotOf(deliveryId);
        Delivery delivery = findDelivery(deliveryId);
        checkCanChange(delivery, lot, BigDecimal.ZERO);
        return markReversed(delivery, reason, manager);
    }

    /**
     * Replaces a wrong delivery: the original is reversed and a corrected delivery is saved in its place, with
     * a new receipt code, in the open lot of its grade, and linked to the original by corrects_id.
     */
    @Transactional
    public Delivery correct(Long deliveryId, CorrectDeliveryRequest request, Manager manager) {
        // Same lock order as a new delivery and closing a lot: the grade first, then the lot.
        Grade grade = gradeRepository.findForUpdateById(request.gradeId())
                .orElseThrow(() -> new BadRequestException("Grade " + request.gradeId() + " does not exist."));
        Lot originalLot = lockLotOf(deliveryId);
        Delivery original = findDelivery(deliveryId);
        Member member = activeMember(request.memberId(), false);

        // A correction that stays in the same open lot puts its kilograms straight back into that lot.
        boolean sameLot = originalLot.getStatus() == LotStatus.OPEN
                && originalLot.getGrade().getId().equals(grade.getId());
        checkCanChange(original, originalLot, sameLot ? request.quantityKg() : BigDecimal.ZERO);
        markReversed(original, request.reason(), manager);

        Delivery corrected = new Delivery();
        corrected.setClientUuid(UUID.randomUUID());
        corrected.setCorrects(original);
        corrected.setMember(member);
        corrected.setLot(lotService.findOrOpenLot(grade));
        corrected.setDeliveredAt(original.getDeliveredAt());
        corrected.setQuantityKg(request.quantityKg());
        corrected.setDeductionAmount(deductionFor(request.quantityKg()));
        corrected.setReceiptCode(ShortCodes.unique("RCT-", deliveryRepository::existsByReceiptCode));
        Delivery saved = deliveryRepository.save(corrected);
        if (original.getConfirmationStatus() == ConfirmationStatus.DISPUTED) {
            original.setResolutionNote("Corrected by " + saved.getReceiptCode() + ": " + request.reason());
        }

        ledgerService.record(LedgerEntryType.DELIVERY, "DELIVERY", saved.getId(), saved.getQuantityKg(),
                saved.getDeductionAmount(),
                "Delivery " + saved.getReceiptCode() + " corrects " + original.getReceiptCode(), manager);
        return saved;
    }

    /** Confirms a PENDING delivery with the member's PIN, for example when the member was not there at first. */
    @Transactional(noRollbackFor = WrongPinException.class)
    public Delivery confirmWithPin(Long deliveryId, String pin) {
        Delivery delivery = findDelivery(deliveryId);
        if (delivery.isReversed()) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " has been reversed.");
        }
        if (delivery.getConfirmationStatus() != ConfirmationStatus.PENDING) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " is "
                    + delivery.getConfirmationStatus() + ". Only a pending delivery can be confirmed with a PIN.");
        }

        Member member = memberRepository.findForUpdateById(delivery.getMember().getId()).orElseThrow();
        switch (memberPinService.check(member, pin)) {
            case CORRECT -> delivery.confirm(ConfirmationMethod.PIN, Instant.now());
            case WRONG -> throw new WrongPinException(member.isPinLocked()
                    ? "Wrong PIN. The PIN is now locked; the manager must reset it."
                    : "Wrong PIN. " + (MemberPinService.MAX_FAILED_ATTEMPTS - member.getPinFailedAttempts())
                            + " attempt(s) left before the PIN is locked.");
            case LOCKED -> throw new IllegalStateException(
                    "The member's PIN is locked after 3 wrong attempts. Reset the PIN first.");
            case NO_PIN, NOT_GIVEN -> throw new BadRequestException(
                    "Member " + member.getMemberCode() + " has no PIN yet. Set a PIN first.");
        }
        return delivery;
    }

    /** Records a member's complaint about a delivery, made in person or by SMS. */
    @Transactional
    public Delivery dispute(Long deliveryId, DisputeReason reason, String note) {
        Delivery delivery = findDelivery(deliveryId);
        if (delivery.isReversed()) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " has been reversed.");
        }
        if (delivery.getConfirmationStatus() == ConfirmationStatus.DISPUTED) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " is already disputed.");
        }
        delivery.dispute(reason, note == null || note.isBlank() ? null : note.trim(), Instant.now());
        return delivery;
    }

    /**
     * Closes a dispute after the manager checked the delivery and found it correct. A wrong delivery is fixed
     * with correct or reverse instead, which also closes the dispute.
     */
    @Transactional
    public Delivery resolveDispute(Long deliveryId, String note) {
        Delivery delivery = findDelivery(deliveryId);
        if (delivery.isReversed()) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " has been reversed.");
        }
        if (delivery.getConfirmationStatus() != ConfirmationStatus.DISPUTED) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " is not disputed.");
        }
        delivery.resolveDispute(note.trim(), Instant.now());
        return delivery;
    }

    public DeliveryResponse toResponse(Delivery delivery) {
        Long correctedById = deliveryRepository.findByCorrectsIdIn(List.of(delivery.getId())).stream()
                .findFirst()
                .map(Delivery::getId)
                .orElse(null);
        return DeliveryResponse.from(delivery, correctedById);
    }

    // Sales and losses lock the lot too, so the checks below cannot be passed by two requests at once.
    private Lot lockLotOf(Long deliveryId) {
        Long lotId = deliveryRepository.findLotIdById(deliveryId)
                .orElseThrow(() -> new EntityNotFoundException("Delivery " + deliveryId + " does not exist."));
        return lotRepository.findForUpdateById(lotId).orElseThrow();
    }

    private Delivery findDelivery(Long deliveryId) {
        return deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new EntityNotFoundException("Delivery " + deliveryId + " does not exist."));
    }

    // addedBackKg is what a correction puts back into the same lot.
    private void checkCanChange(Delivery delivery, Lot lot, BigDecimal addedBackKg) {
        if (delivery.isReversed()) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " has already been reversed.");
        }
        if (saleRepository.existsByLotId(lot.getId())) {
            throw new IllegalStateException("Lot " + lot.getLotCode()
                    + " already has sales, so its deliveries can no longer be reversed or corrected.");
        }
        BigDecimal remainingAfter = lotService.quantitiesOf(lot).remainingKg()
                .subtract(delivery.getQuantityKg())
                .add(addedBackKg);
        if (remainingAfter.signum() < 0) {
            throw new IllegalStateException("Lot " + lot.getLotCode() + " would have less than 0 kg left, because "
                    + "some of its potatoes were already recorded as lost.");
        }
    }

    private Delivery markReversed(Delivery delivery, String reason, Manager manager) {
        LedgerEntry entry = ledgerEntryRepository
                .findFirstByEntryTypeAndRelatedEntityTypeAndRelatedEntityId(
                        LedgerEntryType.DELIVERY, "DELIVERY", delivery.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Delivery " + delivery.getReceiptCode() + " has no ledger entry to reverse."));
        delivery.markReversed(ledgerService.reverse(entry.getId(), reason, manager));
        if (delivery.getConfirmationStatus() == ConfirmationStatus.DISPUTED) {
            delivery.setResolutionNote("Reversed: " + reason);
        }
        return delivery;
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

    private Member activeMember(Long memberId, boolean lock) {
        Optional<Member> found = lock
                ? memberRepository.findForUpdateById(memberId)
                : memberRepository.findById(memberId);
        Member member = found
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
