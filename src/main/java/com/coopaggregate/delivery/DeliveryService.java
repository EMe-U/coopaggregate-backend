package com.coopaggregate.delivery;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.batch.BatchAllocator;
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
import com.coopaggregate.lot.LotFiller;
import com.coopaggregate.lot.LotPortion;
import com.coopaggregate.lot.LotPortionRepository;
import com.coopaggregate.lot.PortionResponse;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;
import com.coopaggregate.member.MemberStatus;
import com.coopaggregate.setting.SettingRepository;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

@Service
public class DeliveryService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final Duration MAX_CLOCK_AHEAD = Duration.ofMinutes(5);
    private static final Duration MAX_SYNC_DELAY = Duration.ofDays(7);
    // TEMPORARY, for the demo only: shown in the delivery's details when SMS_DEMO_CODE confirmed it.
    static final String DEMO_CODE_NOTE = "Confirmed with the demo code, not the member's SMS code.";

    private final DeliveryRepository deliveryRepository;
    private final MemberRepository memberRepository;
    private final GradeRepository gradeRepository;
    private final SettingRepository settingRepository;
    private final LedgerService ledgerService;
    private final LotRepository lotRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final ConfirmationCodes confirmationCodes;
    private final AuditLogRepository auditLogRepository;
    private final BatchAllocator batchAllocator;
    private final LotFiller lotFiller;
    private final LotPortionRepository lotPortionRepository;
    private final ApplicationEventPublisher eventPublisher;

    public DeliveryService(DeliveryRepository deliveryRepository, MemberRepository memberRepository,
                           GradeRepository gradeRepository, SettingRepository settingRepository,
                           LedgerService ledgerService, LotRepository lotRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           ConfirmationCodes confirmationCodes, AuditLogRepository auditLogRepository,
                           BatchAllocator batchAllocator, LotFiller lotFiller,
                           LotPortionRepository lotPortionRepository,
                           ApplicationEventPublisher eventPublisher) {
        this.deliveryRepository = deliveryRepository;
        this.memberRepository = memberRepository;
        this.gradeRepository = gradeRepository;
        this.settingRepository = settingRepository;
        this.ledgerService = ledgerService;
        this.lotRepository = lotRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.confirmationCodes = confirmationCodes;
        this.auditLogRepository = auditLogRepository;
        this.batchAllocator = batchAllocator;
        this.lotFiller = lotFiller;
        this.lotPortionRepository = lotPortionRepository;
        this.eventPublisher = eventPublisher;
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
            return new RecordedDelivery(toResponse(existing.get()), false);
        }

        Instant deliveredAt = request.deliveredAt() != null ? request.deliveredAt() : Instant.now();
        checkDeliveryTime(deliveredAt);
        Member member = activeMember(request.memberId());

        Delivery delivery = new Delivery();
        delivery.setClientUuid(request.clientUuid());
        delivery.setMember(member);
        // No lot yet: a delivery goes into the lots of its grade once it is confirmed (LotFiller).
        delivery.setGrade(grade);
        delivery.setDeliveredAt(deliveredAt);
        delivery.setQuantityKg(request.quantityKg());
        delivery.setDeductionAmount(deductionFor(request.quantityKg()));
        delivery.setReceiptCode(ShortCodes.unique("RCT-", deliveryRepository::existsByReceiptCode));
        Delivery saved = deliveryRepository.save(delivery);

        // The reason is hashed and can never change, so it holds only the receipt code. The member is found
        // through the delivery; a member number in the text would go stale if numbers were ever renamed.
        ledgerService.record(LedgerEntryType.DELIVERY, "DELIVERY", saved.getId(),
                saved.getQuantityKg(), saved.getDeductionAmount(), "Delivery " + saved.getReceiptCode(), manager);
        eventPublisher.publishEvent(new DeliveryRecordedEvent(saved.getId()));
        return new RecordedDelivery(DeliveryResponse.from(saved), true);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeliveryResponse> list(Long memberId, Long lotId, Long gradeId, LocalDate from, LocalDate to,
                                               boolean includeReversed, DeliveryListStatus status, String search,
                                               int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "deliveredAt", "id"));
        Page<Delivery> deliveries = deliveryRepository.findAll(
                matching(memberId, lotId, gradeId, from, to, includeReversed, status, search), pageRequest);
        // The corrections and the lots of the whole page, in two queries.
        List<Long> ids = deliveries.getContent().stream().map(Delivery::getId).toList();
        Map<Long, Long> correctedBy = ids.isEmpty() ? Map.of() : deliveryRepository.findByCorrectsIdIn(ids).stream()
                .collect(Collectors.toMap(corrected -> corrected.getCorrects().getId(), Delivery::getId,
                        (first, second) -> first));
        Map<Long, List<PortionResponse>> lots = ids.isEmpty() ? Map.of()
                : PortionResponse.byDelivery(lotPortionRepository.findWithLotByDeliveryIdIn(ids));
        return PageResponse.from(deliveries.map(delivery -> DeliveryResponse.from(delivery,
                correctedBy.get(delivery.getId()), lots.getOrDefault(delivery.getId(), List.of()))));
    }

    @Transactional(readOnly = true)
    public DeliveryResponse get(Long id) {
        return toResponse(findDelivery(id));
    }

    /**
     * Cancels a delivery: it gets a REVERSAL ledger entry and is no longer counted anywhere. Only allowed while
     * none of its kg are sold, because the money for sold kg was already credited to its member. A delivery that is in
     * lots leaves them: its lost kg go to the other portions of each lot, a lot being filled gets the space back, and a
     * full lot keeps the shortfall (it is never filled again).
     */
    @Transactional
    public Delivery reverse(Long deliveryId, String reason, Manager manager) {
        Delivery delivery = lockForChange(deliveryId, null).delivery();
        checkCanChange(delivery);
        Delivery reversed = markReversed(delivery, reason, manager);
        batchAllocator.releaseLosses(reversed);
        markSoldOutLots(reversed);
        return reversed;
    }

    /** reverse for the API: the response is built before the transaction ends, while lazy data can still load. */
    @Transactional
    public DeliveryResponse reverseDelivery(Long deliveryId, String reason, Manager manager) {
        return toResponse(reverse(deliveryId, reason, manager));
    }

    /**
     * Replaces a wrong delivery: the original is reversed and a corrected delivery is saved in its place, with
     * a new receipt code, linked to the original by corrects_id. Like any new delivery it waits for the member's
     * confirmation and only then goes into a lot.
     */
    @Transactional
    public DeliveryResponse correct(Long deliveryId, CorrectDeliveryRequest request, Manager manager) {
        Locked locked = lockForChange(deliveryId, request.gradeId());
        Delivery original = locked.delivery();
        Grade grade = locked.otherGrade()
                .orElseThrow(() -> new BadRequestException("Grade " + request.gradeId() + " does not exist."));
        Member member = activeMember(request.memberId());

        checkCanChange(original);
        markReversed(original, request.reason(), manager);

        Delivery corrected = new Delivery();
        corrected.setClientUuid(UUID.randomUUID());
        corrected.setCorrects(original);
        corrected.setMember(member);
        corrected.setGrade(grade);
        corrected.setDeliveredAt(original.getDeliveredAt());
        corrected.setQuantityKg(request.quantityKg());
        corrected.setDeductionAmount(deductionFor(request.quantityKg()));
        corrected.setReceiptCode(ShortCodes.unique("RCT-", deliveryRepository::existsByReceiptCode));
        Delivery saved = deliveryRepository.save(corrected);
        batchAllocator.releaseLosses(original);
        markSoldOutLots(original);
        if (original.getConfirmationStatus() == ConfirmationStatus.DISPUTED) {
            original.setResolutionNote("Corrected by " + saved.getReceiptCode() + ": " + request.reason());
        }

        ledgerService.record(LedgerEntryType.DELIVERY, "DELIVERY", saved.getId(), saved.getQuantityKg(),
                saved.getDeductionAmount(),
                "Delivery " + saved.getReceiptCode() + " corrects " + original.getReceiptCode(), manager);
        eventPublisher.publishEvent(new DeliveryRecordedEvent(saved.getId()));
        return toResponse(saved);
    }

    /**
     * Confirms a PENDING delivery with the one-time code from the member's SMS. A wrong code counts as a wrong
     * try; after 3 the code no longer works and the manager must send a new one.
     */
    @Transactional(noRollbackFor = WrongCodeException.class)
    public DeliveryResponse confirmWithCode(Long deliveryId, String code) {
        Delivery delivery = lockForConfirmation(deliveryId);
        if (delivery.isReversed()) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " has been reversed.");
        }
        if (delivery.getConfirmationStatus() != ConfirmationStatus.PENDING) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " is "
                    + delivery.getConfirmationStatus() + ". Only a pending delivery can be confirmed with a code.");
        }
        if (delivery.getConfirmationCodeHash() == null) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode()
                    + " has no valid code. Send the member a new code.");
        }
        if (Instant.now().isAfter(delivery.getConfirmationCodeExpiresAt())) {
            throw new IllegalStateException("The code for delivery " + delivery.getReceiptCode()
                    + " has expired. Send the member a new code.");
        }

        boolean memberCode = confirmationCodes.matches(delivery, code);
        if (memberCode || confirmationCodes.isDemoCode(code)) {
            delivery.confirm(ConfirmationMethod.OTP, Instant.now());
            lotFiller.place(delivery);
            if (!memberCode) {
                // TEMPORARY, for the demo only. The code itself is never written anywhere.
                delivery.setResolutionNote(DEMO_CODE_NOTE);
                auditLogRepository.save(AuditLog.of(AuditAction.DEMO_CODE_CONFIRMATION, delivery.getMember(), null,
                        "Delivery " + delivery.getReceiptCode() + " confirmed with the demo code"));
            }
            eventPublisher.publishEvent(new DeliveryConfirmedEvent(delivery.getId()));
            return toResponse(delivery);
        }
        delivery.recordWrongCode();
        int triesLeft = ConfirmationCodes.MAX_WRONG_TRIES - delivery.getConfirmationCodeFailedAttempts();
        if (triesLeft <= 0) {
            delivery.clearCode();
            throw new WrongCodeException("Wrong code. The code no longer works; send the member a new code.");
        }
        throw new WrongCodeException("Wrong code. " + triesLeft + " tr" + (triesLeft == 1 ? "y" : "ies")
                + " left.");
    }

    /** Sends the member a new code for a PENDING delivery. The old code stops working; at most 3 per delivery. */
    @Transactional
    public DeliveryResponse resendCode(Long deliveryId, Manager manager) {
        Delivery delivery = deliveryRepository.findForUpdateById(deliveryId)
                .orElseThrow(() -> new EntityNotFoundException("Delivery " + deliveryId + " does not exist."));
        if (delivery.isReversed()) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " has been reversed.");
        }
        if (delivery.getConfirmationStatus() != ConfirmationStatus.PENDING) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " is "
                    + delivery.getConfirmationStatus() + ". A code is only sent for a pending delivery.");
        }
        if (!settingRepository.findById(1L).map(setting -> setting.isSmsEnabled()).orElse(false)) {
            throw new IllegalStateException("SMS are turned off, so no code can be sent. Turn SMS on in Settings, "
                    + "or confirm the delivery with a manager review.");
        }
        if (delivery.getConfirmationCodeResends() >= ConfirmationCodes.MAX_RESENDS) {
            throw new IllegalStateException("A new code was already sent " + ConfirmationCodes.MAX_RESENDS
                    + " times for delivery " + delivery.getReceiptCode() + ".");
        }

        delivery.recordResend();
        auditLogRepository.save(AuditLog.of(AuditAction.CONFIRMATION_CODE_RESENT, delivery.getMember(), manager,
                "Delivery " + delivery.getReceiptCode()));
        eventPublisher.publishEvent(new ConfirmationCodeResentEvent(delivery.getId(),
                delivery.getConfirmationCodeResends()));
        return toResponse(delivery);
    }

    /**
     * Records a member's complaint about a delivery, made in person or by SMS. A delivery disputed after it went into a
     * lot keeps its kg there, but they are held back from sales until the dispute is settled.
     */
    @Transactional
    public DeliveryResponse dispute(Long deliveryId, DisputeReason reason, String note) {
        // The same locks as a confirmation: sales of the grade wait, so none can sell kg that are being disputed.
        Delivery delivery = lockForConfirmation(deliveryId);
        if (delivery.isReversed()) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " has been reversed.");
        }
        if (delivery.getConfirmationStatus() == ConfirmationStatus.DISPUTED) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " is already disputed.");
        }
        delivery.dispute(reason, note == null || note.isBlank() ? null : note.trim(), Instant.now());
        return toResponse(delivery);
    }

    /**
     * Closes a dispute after the manager checked the delivery and found it correct. A wrong delivery is fixed
     * with correct or reverse instead, which also closes the dispute.
     */
    @Transactional
    public DeliveryResponse resolveDispute(Long deliveryId, String note) {
        Delivery delivery = lockForConfirmation(deliveryId);
        if (delivery.isReversed()) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " has been reversed.");
        }
        if (delivery.getConfirmationStatus() != ConfirmationStatus.DISPUTED) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " is not disputed.");
        }
        delivery.confirmByManagerReview(note.trim(), Instant.now());
        // A delivery disputed after it was confirmed is already in a lot and stays where it is.
        lotFiller.place(delivery);
        eventPublisher.publishEvent(new DeliveryConfirmedEvent(delivery.getId()));
        return toResponse(delivery);
    }

    /**
     * The manager confirms a pending delivery after checking it, when the member cannot or did not confirm it: SMS
     * are turned off, all 3 new codes were already sent, or it has been pending for more than 48 hours since it
     * reached the server. In every other case the member confirms with the code. The member can still dispute the
     * delivery afterwards by SMS.
     */
    @Transactional
    public DeliveryResponse reviewPending(Long deliveryId, String note, Manager manager) {
        Delivery delivery = lockForConfirmation(deliveryId);
        if (delivery.isReversed()) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " has been reversed.");
        }
        if (delivery.getConfirmationStatus() != ConfirmationStatus.PENDING) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " is "
                    + delivery.getConfirmationStatus() + ". Only a pending delivery can be reviewed; a disputed one "
                    + "is closed with resolve-dispute.");
        }
        boolean smsEnabled = settingRepository.findById(1L).map(setting -> setting.isSmsEnabled()).orElse(false);
        int resendsLeft = ConfirmationCodes.MAX_RESENDS - delivery.getConfirmationCodeResends();
        boolean overdue = delivery.getCreatedAt() != null
                && delivery.getCreatedAt().isBefore(Instant.now().minus(Delivery.PENDING_TOO_LONG));
        if (smsEnabled && resendsLeft > 0 && !overdue) {
            throw new IllegalStateException("SMS are turned on, so the member confirms with the code from the SMS. "
                    + "Send a new code if it did not arrive (" + resendsLeft + " left), or review the delivery once "
                    + "it has been pending for 48 hours.");
        }
        delivery.confirmByManagerReview(note.trim(), Instant.now());
        lotFiller.place(delivery);
        auditLogRepository.save(AuditLog.of(AuditAction.DELIVERY_REVIEWED, delivery.getMember(), manager,
                "Delivery " + delivery.getReceiptCode() + ": " + note.trim()));
        eventPublisher.publishEvent(new DeliveryConfirmedEvent(delivery.getId()));
        return toResponse(delivery);
    }

    // Reads the delivery's member, lot and grade, which may be lazy. Only call it inside a transaction, and
    // return the response instead of the entity: open-in-view is off, so nothing loads once the method returns.
    DeliveryResponse toResponse(Delivery delivery) {
        Long correctedById = deliveryRepository.findByCorrectsIdIn(List.of(delivery.getId())).stream()
                .findFirst()
                .map(Delivery::getId)
                .orElse(null);
        return DeliveryResponse.from(delivery, correctedById, PortionResponse.byDelivery(
                lotPortionRepository.findWithLotByDeliveryIdIn(List.of(delivery.getId())))
                .getOrDefault(delivery.getId(), List.of()));
    }

    /**
     * A confirmation puts the delivery into a lot, so it takes the locks in the same order as sales and losses: the
     * grade, the grade's lot being filled, then the delivery. The grade lock also keeps two confirmations of one grade
     * from filling a lot past its capacity.
     */
    private Delivery lockForConfirmation(Long deliveryId) {
        Long gradeId = deliveryRepository.findGradeIdById(deliveryId)
                .orElseThrow(() -> new EntityNotFoundException("Delivery " + deliveryId + " does not exist."));
        gradeRepository.findForUpdateById(gradeId);
        lotFiller.lockFillingLot(gradeId);
        return deliveryRepository.findForUpdateById(deliveryId).orElseThrow();
    }

    /**
     * A reversal or correction takes the locks in the same order as sales and losses, so a sale that ran first is
     * seen with the kg it sold: the grade (and a correction's new grade, both in id order), every lot the delivery is
     * in (its own lot from before fixed-size lots, or the lots of its portions, in id order), then the delivery. The
     * allocator locks the other portions it hands lost kg to next.
     */
    private Locked lockForChange(Long deliveryId, Long otherGradeId) {
        Long gradeId = deliveryRepository.findGradeIdById(deliveryId)
                .orElseThrow(() -> new EntityNotFoundException("Delivery " + deliveryId + " does not exist."));
        Set<Long> gradeIds = new TreeSet<>(List.of(gradeId));
        if (otherGradeId != null) {
            gradeIds.add(otherGradeId);
        }
        Optional<Grade> otherGrade = Optional.empty();
        for (Long id : gradeIds) {
            Optional<Grade> grade = gradeRepository.findForUpdateById(id);
            if (id.equals(otherGradeId)) {
                otherGrade = grade;
            }
        }
        Set<Long> lotIds = new TreeSet<>();
        deliveryRepository.findLotIdById(deliveryId).ifPresent(lotIds::add);
        lotPortionRepository.findByDeliveryIdOrderById(deliveryId)
                .forEach(portion -> lotIds.add(portion.getLot().getId()));
        lotIds.forEach(lotRepository::findForUpdateById);
        return new Locked(deliveryRepository.findForUpdateById(deliveryId).orElseThrow(), otherGrade);
    }

    /** The delivery being reversed or corrected, and a correction's new grade (empty when it does not exist). */
    private record Locked(Delivery delivery, Optional<Grade> otherGrade) {
    }

    // A full lot that the reversal left with nothing to sell is SOLD when some of its kg were sold, and EMPTIED when
    // none were (all its deliveries reversed or corrected first). A lot being filled stays FILLING.
    private void markSoldOutLots(Delivery reversed) {
        for (LotPortion portion : lotPortionRepository.findByDeliveryIdOrderById(reversed.getId())) {
            Lot lot = portion.getLot();
            if (lot.getStatus() == LotStatus.FULL && lotPortionRepository.remainingKg(lot.getId()).signum() == 0) {
                lot.setStatus(lotPortionRepository.soldKg(lot.getId()).signum() > 0 ? LotStatus.SOLD
                        : LotStatus.EMPTIED);
            }
        }
    }

    private Delivery findDelivery(Long deliveryId) {
        return deliveryRepository.findById(deliveryId)
                .orElseThrow(() -> new EntityNotFoundException("Delivery " + deliveryId + " does not exist."));
    }

    // Any kg sold already paid their member, so the delivery can no longer change. Its lost kg must find room in the
    // other portions of each lot it is in. A waiting delivery, or one disputed before it was confirmed, is in no lot.
    private void checkCanChange(Delivery delivery) {
        if (delivery.isReversed()) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " has already been reversed.");
        }
        if (delivery.getSoldKg().signum() > 0) {
            throw new IllegalStateException("Delivery " + delivery.getReceiptCode() + " already has "
                    + delivery.getSoldKg().toPlainString() + " kg sold, so it can no longer be reversed or corrected.");
        }
        // The money of a shared lot (from before fixed-size lots) was divided by the kg of all its deliveries.
        Lot ownLot = delivery.getLot();
        if (ownLot != null && ownLot.getStatus() == LotStatus.SHARED) {
            throw new IllegalStateException("Lot " + ownLot.getLotCode()
                    + " has already been shared, so its deliveries can no longer be reversed or corrected.");
        }
        for (LotPortion portion : lotPortionRepository.findByDeliveryIdOrderById(delivery.getId())) {
            if (portion.getLostKg().signum() > 0
                    && batchAllocator.lossCarryingKg(portion.getLot(), delivery).compareTo(portion.getLostKg()) < 0) {
                throw new IllegalStateException("Lot " + portion.getLot().getLotCode() + " would have less than 0 kg "
                        + "left, because some of its potatoes were already recorded as lost.");
            }
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


    // from and to are whole days in Kigali time; to is inclusive. A status overrides includeReversed: REVERSED lists
    // only reversed deliveries, the other statuses only deliveries that are not reversed.
    static Specification<Delivery> matching(Long memberId, Long lotId, Long gradeId, LocalDate from, LocalDate to,
                                            boolean includeReversed, DeliveryListStatus status, String search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status == DeliveryListStatus.REVERSED) {
                predicates.add(cb.isNotNull(root.get("reversedAt")));
            } else if (status != null) {
                predicates.add(cb.equal(root.get("confirmationStatus"), status.confirmationStatus()));
                predicates.add(cb.isNull(root.get("reversedAt")));
            } else if (!includeReversed) {
                predicates.add(cb.isNull(root.get("reversedAt")));
            }
            if (memberId != null) {
                predicates.add(cb.equal(root.get("member").get("id"), memberId));
            }
            if (gradeId != null) {
                predicates.add(cb.equal(root.get("grade").get("id"), gradeId));
            }
            if (search != null && !search.isBlank()) {
                String like = "%" + search.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("member").get("fullName")), like),
                        cb.like(cb.lower(root.get("member").get("memberCode")), like),
                        cb.like(cb.lower(root.get("receiptCode")), like)));
            }
            if (lotId != null) {
                // Its own lot (from before fixed-size lots), or a lot it has a portion in.
                Subquery<Long> portions = query.subquery(Long.class);
                Root<LotPortion> portion = portions.from(LotPortion.class);
                portions.select(portion.<Long>get("id")).where(
                        cb.equal(portion.get("delivery").get("id"), root.get("id")),
                        cb.equal(portion.get("lot").get("id"), lotId));
                predicates.add(cb.or(cb.equal(root.get("lot").get("id"), lotId), cb.exists(portions)));
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
