package com.coopaggregate.member;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.common.PageResponse;
import com.coopaggregate.common.RwandanPhoneNumber;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.Predicate;

@Service
public class MemberService {

    private static final int MAX_PAGE_SIZE = 100;

    private final MemberRepository memberRepository;

    public MemberService(MemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<MemberResponse> list(String search, Boolean active, int page, int size) {
        // Sorting by id as well keeps the order stable between pages when two members share a name.
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by("fullName").and(Sort.by("id")));
        return PageResponse.from(memberRepository.findAll(matching(search, active), pageRequest)
                .map(MemberResponse::from));
    }

    @Transactional(readOnly = true)
    public MemberResponse get(Long id) {
        return MemberResponse.from(findMember(id));
    }

    @Transactional
    public MemberResponse create(MemberRequest request) {
        Member member = new Member();
        applyDetails(member, request);
        member.setJoinDate(request.joinDate() != null ? request.joinDate() : LocalDate.now());
        // Taken after the duplicate checks so a rejected request does not use up a code.
        member.setMemberCode(String.format("MEM-%04d", memberRepository.nextMemberCodeNumber()));
        return MemberResponse.from(memberRepository.save(member));
    }

    @Transactional
    public MemberResponse update(Long id, MemberRequest request) {
        Member member = findMember(id);
        applyDetails(member, request);
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

    private void applyDetails(Member member, MemberRequest request) {
        String phone = RwandanPhoneNumber.normalize(request.phone());
        String nationalId = trimToNull(request.nationalId());

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

        member.setFullName(request.fullName().trim());
        member.setPhone(phone);
        member.setNationalId(nationalId);
        member.setAddress(trimToNull(request.address()));
        if (request.preferredLanguage() != null) {
            member.setPreferredLanguage(request.preferredLanguage());
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
                        cb.like(root.get("phone"), "%" + phoneTerm + "%")));
            }

            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
