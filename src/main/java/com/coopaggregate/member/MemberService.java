package com.coopaggregate.member;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.identity.IdentityAvailability;
import com.coopaggregate.identity.IdentityErrorCode;
import com.coopaggregate.identity.IdentityProviderException;
import com.coopaggregate.identity.IdentityRequestException;
import com.coopaggregate.identity.IdentityVerificationService;
import com.coopaggregate.identity.VerifiedDetails;
import com.coopaggregate.manager.Manager;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;

@Service
public class MemberService {

    private static final int MAX_PAGE_SIZE = 100;
    // ASCII digits only ([0-9], not \d), exactly 16.
    private static final java.util.regex.Pattern NATIONAL_ID = java.util.regex.Pattern.compile("[0-9]{16}");

    private final MemberRepository memberRepository;
    private final IdentityAvailability identityAvailability;
    private final IdentityVerificationService identityVerificationService;
    private final AuditLogRepository auditLogRepository;

    public MemberService(MemberRepository memberRepository, IdentityAvailability identityAvailability,
                         IdentityVerificationService identityVerificationService,
                         AuditLogRepository auditLogRepository) {
        this.memberRepository = memberRepository;
        this.identityAvailability = identityAvailability;
        this.identityVerificationService = identityVerificationService;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<MemberListItem> list(String search, Boolean active, int page, int size) {
        // Sorting by id as well keeps the order stable between pages when two members share a name.
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by("fullName").and(Sort.by("id")));
        return PageResponse.from(memberRepository.findAll(matching(search, active), pageRequest)
                .map(MemberListItem::from));
    }

    @Transactional(readOnly = true)
    public MemberSummaryResponse summary() {
        return summary(KigaliTime.today());
    }

    // Takes today's date as a parameter so tests can use a fixed month.
    MemberSummaryResponse summary(LocalDate today) {
        LocalDate firstDayOfMonth = today.withDayOfMonth(1);
        LocalDate lastDayOfMonth = today.with(TemporalAdjusters.lastDayOfMonth());
        return new MemberSummaryResponse(
                memberRepository.count(),
                memberRepository.countByStatus(MemberStatus.ACTIVE),
                memberRepository.countByStatus(MemberStatus.INACTIVE),
                memberRepository.countByJoinDateBetween(firstDayOfMonth, lastDayOfMonth));
    }

    @Transactional(readOnly = true)
    public MemberResponse get(Long id) {
        return MemberResponse.from(findMember(id));
    }

    /**
     * Registers a member from a completed identity verification: the name and phone are the verified ones, never typed.
     * Everything happens in one transaction, so a registration refused for another reason (a phone or national ID
     * already used) leaves the verification usable until its 30 minutes are over.
     */
    @Transactional
    public MemberResponse create(NewMemberRequest request, Manager manager) {
        // Every member is registered with a verified identity. Without the identity provider nobody can be registered,
        // unless MEMBER_REGISTER_UNVERIFIED allows a member who is stored as not verified. A provider that is on but
        // cannot be reached never leads here: that is only decided by the settings.
        if (!identityAvailability.isAvailable()) {
            if (identityAvailability.isUnverifiedAllowed()) {
                return createUnverified(request, manager);
            }
            throw IdentityProviderException.notConfigured(
                    "Member registration needs identity verification, which is not configured.");
        }
        if (request.verificationId() == null) {
            throw new BadRequestException("verificationId is required. Verify the person's identity first.");
        }
        VerifiedDetails verified = identityVerificationService.verifiedDetails(request.verificationId(), manager);
        refuseVerifiedFieldsInTheRequest(request, verified, Set.of("address"));
        String nationalId = requiredNationalId(request.nationalId());
        Member member = new Member();
        applyDetails(member, verified.fullName(), verified.phone(), nationalId,
                verified.address() != null ? verified.address() : request.address(), request.preferredLanguage());
        member.setVerifiedDetails(verified.birthdate(), verified.gender(), verified.email());
        if (verified.address() != null) {
            member.setVerifiedAddress(verified.address());
        }
        member.setJoinDate(request.joinDate() != null ? request.joinDate() : KigaliTime.today());
        // Continues from the highest existing number. If two members were created at the same moment and got
        // the same code, the unique member_code would reject the second one with 409.
        member.setMemberCode(String.format("M-%04d", memberRepository.highestMemberNumber() + 1));
        Member saved = memberRepository.save(member);
        identityVerificationService.attach(request.verificationId(), manager, saved);
        return MemberResponse.from(saved);
    }

    // FOR DEMOS AND FALLBACKS ONLY (MEMBER_REGISTER_UNVERIFIED=true with MOSIP_ENABLED=false): the name and phone are
    // typed, with the same rules as editing a member, and the member is stored as not verified (identity_verified
    // false, no verification method, verifiedFields empty). The birth date, gender and email only ever come from the
    // identity check, so they are refused here too. An audit row records the registration.
    private MemberResponse createUnverified(NewMemberRequest request, Manager manager) {
        if (request.verificationId() != null) {
            throw new BadRequestException("verificationId: must not be sent: identity verification is switched off");
        }
        String fullName = request.fullName() == null ? "" : request.fullName().trim();
        if (fullName.isEmpty()) {
            throw new BadRequestException("fullName: is required");
        }
        if (fullName.length() > 150) {
            throw new BadRequestException("fullName: must be at most 150 characters");
        }
        if (request.phone() == null || request.phone().isBlank()) {
            throw new BadRequestException("phone: is required");
        }
        if (!RwandanPhoneNumber.isValid(request.phone())) {
            throw new BadRequestException(
                    "phone: must be a Rwandan mobile number: 07XXXXXXXX, 2507XXXXXXXX or +2507XXXXXXXX");
        }
        refuseVerifiedFieldsInTheRequest(request, VerifiedDetails.NONE, Set.of("fullName", "phone", "address"));
        String nationalId = requiredNationalId(request.nationalId());
        Member member = new Member();
        applyDetails(member, fullName, request.phone(), nationalId, request.address(),
                request.preferredLanguage());
        member.setJoinDate(request.joinDate() != null ? request.joinDate() : KigaliTime.today());
        member.setMemberCode(String.format("M-%04d", memberRepository.highestMemberNumber() + 1));
        Member saved = memberRepository.save(member);
        auditLogRepository.save(AuditLog.of(AuditAction.MEMBER_REGISTERED_UNVERIFIED, saved, manager,
                "registered without identity verification"));
        return MemberResponse.from(saved);
    }

    // A detail the identity check verified comes only from the verification. A value for it in the request is refused,
    // whatever it is, and the message never repeats the verified value. A birth date, gender or email that was not
    // verified cannot be typed either: those only come from the check. Any value counts, even an empty one (as before
    // for the name and phone); only a field left out or null is not sent. typable names the fields that may be typed
    // when the check did not verify them.
    private static void refuseVerifiedFieldsInTheRequest(NewMemberRequest request, VerifiedDetails verified,
                                                         Set<String> typable) {
        Set<String> verifiedFields = Set.copyOf(verified.verifiedFields());
        Map<String, Object> sent = new LinkedHashMap<>();
        sent.put("fullName", request.fullName());
        sent.put("phone", request.phone());
        sent.put("birthdate", request.birthDate());
        sent.put("gender", request.gender());
        sent.put("email", request.email());
        sent.put("address", request.address());
        for (Map.Entry<String, Object> field : sent.entrySet()) {
            if (field.getValue() == null) {
                continue;
            }
            if (verifiedFields.contains(field.getKey())) {
                throw new IdentityRequestException(IdentityErrorCode.VERIFIED_FIELD_LOCKED,
                        "This detail came from the identity check and cannot be changed here.");
            }
            if (!typable.contains(field.getKey())) {
                throw new BadRequestException((field.getKey().equals("birthdate") ? "birthDate" : field.getKey())
                        + ": must not be sent: it only comes from the identity check");
            }
        }
    }

    @Transactional
    public MemberResponse update(Long id, MemberRequest request) {
        Member member = findMember(id);
        // A changed address is no longer the verified one.
        if (member.isAddressVerified() && !Objects.equals(member.getAddress(), trimToNull(request.address()))) {
            member.unmarkAddressVerified();
        }
        applyDetails(member, request.fullName(), request.phone(), nationalIdForUpdate(member, request.nationalId()),
                request.address(), request.preferredLanguage());
        if (request.joinDate() != null) {
            member.setJoinDate(request.joinDate());
        }
        return MemberResponse.from(member);
    }

    @Transactional
    public MemberResponse setActive(Long id, boolean active) {
        Member member = findMember(id);
        member.setStatus(active ? MemberStatus.ACTIVE : MemberStatus.INACTIVE);
        return MemberResponse.from(member);
    }

    // A new member needs a national ID: exactly 16 digits once the spaces around it are trimmed. The messages never
    // repeat the value.
    private static String requiredNationalId(String raw) {
        String nationalId = trimToNull(raw);
        if (nationalId == null) {
            throw new NationalIdException(NationalIdErrorCode.NATIONAL_ID_REQUIRED, "nationalId: is required");
        }
        return validNationalId(nationalId);
    }

    // Members registered before the national ID was required may have none. An edit may add one, or change it to
    // another valid one, but never remove one that is set. The stored value sent back unchanged is always accepted,
    // so a member with an older ID in another format can still be edited.
    private static String nationalIdForUpdate(Member member, String raw) {
        String current = trimToNull(member.getNationalId());
        String nationalId = trimToNull(raw);
        if (nationalId == null) {
            if (current != null) {
                throw new NationalIdException(NationalIdErrorCode.NATIONAL_ID_REQUIRED,
                        "nationalId: cannot be removed once it is set");
            }
            return null;
        }
        return nationalId.equals(current) ? current : validNationalId(nationalId);
    }

    private static String validNationalId(String nationalId) {
        if (!NATIONAL_ID.matcher(nationalId).matches()) {
            throw new NationalIdException(NationalIdErrorCode.NATIONAL_ID_INVALID,
                    "nationalId: must be exactly 16 digits");
        }
        return nationalId;
    }

    // nationalId is already checked (see requiredNationalId and nationalIdForUpdate); null means the member has none.
    private void applyDetails(Member member, String fullName, String rawPhone, String nationalId, String address,
                              String preferredLanguage) {
        String phone = RwandanPhoneNumber.normalize(rawPhone);

        memberRepository.findByPhone(phone)
                .filter(other -> !other.getId().equals(member.getId()))
                .ifPresent(other -> {
                    throw new IllegalStateException(
                            "Phone number " + phone + " is already used by member " + other.getMemberCode() + ".");
                });
        if (nationalId != null) {
            memberRepository.findByNationalId(nationalId)
                    .filter(other -> !other.getId().equals(member.getId()))
                    .ifPresent(other -> {
                        throw new NationalIdTakenException(other.getMemberCode());
                    });
        }

        member.setFullName(fullName.trim());
        member.setPhone(phone);
        member.setNationalId(nationalId);
        member.setAddress(trimToNull(address));
        if (preferredLanguage != null) {
            member.setPreferredLanguage(preferredLanguage);
        }
    }

    private Member findMember(Long id) {
        return memberRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Member " + id + " does not exist."));
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static Specification<Member> matching(String search, Boolean active) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (active != null) {
                predicates.add(cb.equal(root.get("status"), active ? MemberStatus.ACTIVE : MemberStatus.INACTIVE));
            }

            if (search != null && !search.isBlank()) {
                String term = search.trim();
                String textPattern = "%" + term.toLowerCase(Locale.ROOT) + "%";
                // Phones are stored as +2507XXXXXXXX, so a search for 07XXXXXXXX must be converted first.
                String phoneTerm = RwandanPhoneNumber.isValid(term) ? RwandanPhoneNumber.normalize(term) : term;
                List<Predicate> any = new ArrayList<>();
                any.add(cb.like(cb.lower(root.get("fullName")), textPattern));
                any.add(cb.like(cb.lower(root.get("memberCode")), textPattern));
                any.add(cb.like(root.get("phone"), "%" + phoneTerm + "%"));
                // The national ID only matches as a whole: part of an ID never finds a member.
                if (NATIONAL_ID.matcher(term).matches()) {
                    any.add(cb.equal(root.get("nationalId"), term));
                }
                predicates.add(cb.or(any.toArray(Predicate[]::new)));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
