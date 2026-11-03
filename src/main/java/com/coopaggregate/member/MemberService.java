package com.coopaggregate.member;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.common.PageResponse;
import com.coopaggregate.common.RwandanPhoneNumber;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.identity.IdentityAvailability;
import com.coopaggregate.identity.IdentityProviderException;
import com.coopaggregate.identity.IdentityVerificationService;
import com.coopaggregate.identity.VerifiedDetails;
import com.coopaggregate.manager.Manager;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;

@Service
public class MemberService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MemberRepository memberRepository;
    private final IdentityAvailability identityAvailability;
    private final IdentityVerificationService identityVerificationService;

    public MemberService(MemberRepository memberRepository, IdentityAvailability identityAvailability,
                         IdentityVerificationService identityVerificationService) {
        this.memberRepository = memberRepository;
        this.identityAvailability = identityAvailability;
        this.identityVerificationService = identityVerificationService;
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
        // Every member is registered with a verified identity. Without the identity provider nobody can be registered.
        if (!identityAvailability.isAvailable()) {
            throw IdentityProviderException.notConfigured(
                    "Member registration needs identity verification, which is not configured.");
        }
        if (request.verificationId() == null) {
            throw new BadRequestException("verificationId is required. Verify the person's identity first.");
        }
        VerifiedDetails verified = identityVerificationService.verifiedDetails(request.verificationId(), manager);
        Member member = new Member();
        applyDetails(member, verified.fullName(), verified.phone(), request.nationalId(), request.address(),
                request.preferredLanguage());
        member.setJoinDate(request.joinDate() != null ? request.joinDate() : KigaliTime.today());
        // Continues from the highest existing number. If two members were created at the same moment and got
        // the same code, the unique member_code would reject the second one with 409.
        member.setMemberCode(String.format("M-%04d", memberRepository.highestMemberNumber() + 1));
        Member saved = memberRepository.save(member);
        identityVerificationService.attach(request.verificationId(), manager, saved);
        return MemberResponse.from(saved);
    }

    @Transactional
    public MemberResponse update(Long id, MemberRequest request) {
        Member member = findMember(id);
        applyDetails(member, request.fullName(), request.phone(), request.nationalId(), request.address(),
                request.preferredLanguage());
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

    private void applyDetails(Member member, String fullName, String rawPhone, String rawNationalId, String address,
                              String preferredLanguage) {
        String phone = RwandanPhoneNumber.normalize(rawPhone);
        String nationalId = trimToNull(rawNationalId);

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
                        throw new IllegalStateException(
                                "National ID " + nationalId + " is already used by member " + other.getMemberCode() + ".");
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
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("fullName")), textPattern),
                        cb.like(cb.lower(root.get("memberCode")), textPattern),
                        cb.like(root.get("phone"), "%" + phoneTerm + "%"),
                        cb.like(root.get("nationalId"), "%" + term + "%")));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
