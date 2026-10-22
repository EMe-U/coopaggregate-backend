package com.coopaggregate.buyer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.common.PageResponse;
import com.coopaggregate.common.RwandanPhoneNumber;
import com.coopaggregate.common.ShortCodes;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.setting.SettingRepository;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;

/** Purchase requests from buyers. The public side creates and looks them up; the manager side works on them. */
@Service
public class BuyerRequestService {

    /** The same for an unknown code and a wrong phone, so a code cannot be tested without its phone. */
    public static final String NOT_FOUND = "Request not found.";

    private static final int MAX_PAGE_SIZE = 100;

    private final BuyerRequestRepository requestRepository;
    private final GradeRepository gradeRepository;
    private final SettingRepository settingRepository;
    private final BuyerRepository buyerRepository;
    private final AuditLogRepository auditLogRepository;

    public BuyerRequestService(BuyerRequestRepository requestRepository, GradeRepository gradeRepository,
                               SettingRepository settingRepository, BuyerRepository buyerRepository,
                               AuditLogRepository auditLogRepository) {
        this.requestRepository = requestRepository;
        this.gradeRepository = gradeRepository;
        this.settingRepository = settingRepository;
        this.buyerRepository = buyerRepository;
        this.auditLogRepository = auditLogRepository;
    }

    /** Saves a request from the public page as PENDING, with a code like BR-2026-7K2Q. */
    @Transactional
    public BuyerRequest createFromPublic(String name, String phone, String email, String gradeCode,
                                         BigDecimal quantityKg, String note) {
        Grade grade = gradeRepository.findByCodeAndActiveTrue(gradeCode.trim().toUpperCase())
                .orElseThrow(() -> new BadRequestException("Unknown grade. Choose one of the grades shown."));
        BigDecimal minOrderKg = settingRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("The settings row is missing."))
                .getMinOrderKg();
        if (quantityKg.compareTo(minOrderKg) < 0) {
            throw new BadRequestException("The smallest order is " + minOrderKg.stripTrailingZeros().toPlainString()
                    + " kg.");
        }

        BuyerRequest request = new BuyerRequest();
        request.setReferenceCode(newCode());
        request.setGrade(grade);
        request.setQuantityKg(quantityKg);
        request.setRequesterName(name.trim());
        request.setRequesterPhone(RwandanPhoneNumber.normalize(phone));
        request.setRequesterEmail(blankToNull(email));
        request.setMessage(blankToNull(note));
        return requestRepository.save(request);
    }

    /** The request, only if the phone is the one it was sent with. Otherwise the same 404 as an unknown code. */
    @Transactional(readOnly = true)
    public BuyerRequest findForPublic(String code, String phone) {
        if (code == null || !RwandanPhoneNumber.isValid(phone)) {
            throw new EntityNotFoundException(NOT_FOUND);
        }
        BuyerRequest request = requestRepository.findByReferenceCode(code.trim().toUpperCase())
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND));
        byte[] given = RwandanPhoneNumber.normalize(phone).getBytes(StandardCharsets.UTF_8);
        byte[] stored = request.getRequesterPhone() == null ? new byte[0]
                : request.getRequesterPhone().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, stored)) {
            throw new EntityNotFoundException(NOT_FOUND);
        }
        return request;
    }

    // --- The manager's side. Responses are built here, inside the transaction, where lazy data can still load. ---

    @Transactional(readOnly = true)
    public PageResponse<BuyerRequestListItem> list(BuyerRequestStatus status, String search, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return PageResponse.from(requestRepository.findAll(matching(status, search), pageRequest)
                .map(BuyerRequestListItem::from));
    }

    @Transactional(readOnly = true)
    public BuyerRequestDetail get(Long id) {
        return BuyerRequestDetail.from(find(id));
    }

    /** Changes the status and, when given, the message the buyer sees. The change is written to the history. */
    @Transactional
    public BuyerRequestDetail changeStatus(Long id, BuyerRequestStatusUpdate update, Manager manager) {
        BuyerRequest request = requestRepository.findForUpdateById(id)
                .orElseThrow(() -> new EntityNotFoundException("Buyer request " + id + " does not exist."));
        String message = blankToNull(update.publicMessage());
        BuyerRequestStatus before = request.getStatus();
        boolean messageChanged = message != null && !message.equals(request.getPublicMessage());
        if (before == update.status() && !messageChanged) {
            throw new IllegalStateException("Request " + request.getReferenceCode() + " is already "
                    + before + ". Send a new public message to change what the buyer reads.");
        }

        request.setStatus(update.status());
        if (message != null) {
            request.setPublicMessage(message);
        }
        request.setUpdatedAt(Instant.now());
        // The message itself is not copied into the history: it is for the buyer and may hold contact details.
        auditLogRepository.save(AuditLog.of(AuditAction.BUYER_REQUEST_STATUS_CHANGED, null, manager,
                request.getReferenceCode() + ": " + before + " -> " + update.status()
                        + (messageChanged ? "; public message changed" : "")));
        return BuyerRequestDetail.from(request);
    }

    /**
     * Turns a public request into a buyer record, or links it to the buyer who already has that phone number. The
     * manager can then record the sale with POST /api/sales/orders for that buyer.
     */
    @Transactional
    public BuyerRequestDetail convertToBuyer(Long id, Manager manager) {
        BuyerRequest request = requestRepository.findForUpdateById(id)
                .orElseThrow(() -> new EntityNotFoundException("Buyer request " + id + " does not exist."));
        if (request.getBuyer() != null) {
            throw new IllegalStateException("Request " + request.getReferenceCode() + " already has a buyer.");
        }

        Optional<Buyer> existing = buyerRepository.findByPhone(request.getRequesterPhone());
        Buyer buyer = existing.orElseGet(() -> {
            Buyer created = new Buyer();
            created.setName(request.getRequesterName());
            created.setPhone(request.getRequesterPhone());
            created.setEmail(request.getRequesterEmail());
            return buyerRepository.save(created);
        });
        request.setBuyer(buyer);
        request.setUpdatedAt(Instant.now());
        auditLogRepository.save(AuditLog.of(AuditAction.BUYER_REQUEST_CONVERTED, null, manager,
                request.getReferenceCode() + (existing.isPresent() ? ": linked to an existing buyer"
                        : ": new buyer record created")));
        return BuyerRequestDetail.from(request);
    }

    private BuyerRequest find(Long id) {
        return requestRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Buyer request " + id + " does not exist."));
    }

    static Specification<BuyerRequest> matching(BuyerRequestStatus status, String search) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (search != null && !search.isBlank()) {
                String term = search.trim();
                // Phones are stored as +2507XXXXXXXX, so a search for 07XXXXXXXX must be converted first.
                String phoneTerm = RwandanPhoneNumber.isValid(term) ? RwandanPhoneNumber.normalize(term) : term;
                String like = "%" + term.toLowerCase(Locale.ROOT) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("referenceCode")), like),
                        cb.like(cb.lower(root.get("requesterName")), like),
                        cb.like(root.get("requesterPhone"), "%" + phoneTerm + "%")));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    /** BR-2026-XXXX: random, from letters and digits that are not confused with each other. */
    String newCode() {
        return ShortCodes.unique("BR-" + KigaliTime.today().getYear() + "-", requestRepository::existsByReferenceCode);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
