package com.coopaggregate.payment;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.common.RwandanPhoneNumber;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;

@Service
public class PaymentService {

    private final ProducePaymentRepository paymentRepository;
    private final MemberRepository memberRepository;

    public PaymentService(ProducePaymentRepository paymentRepository, MemberRepository memberRepository) {
        this.paymentRepository = paymentRepository;
        this.memberRepository = memberRepository;
    }

    // Owed = shares minus deductions minus payments. Members with nothing owed are left out.
    @Transactional(readOnly = true)
    public OwedMembersResponse owedMembers(String search) {
        Map<Long, Long> owedByMember = new HashMap<>();
        for (MemberAmount netShare : paymentRepository.findNetSharesByMember()) {
            owedByMember.merge(netShare.memberId(), netShare.amountRwf(), Long::sum);
        }
        for (MemberAmount paid : paymentRepository.findPaymentsByMember()) {
            owedByMember.merge(paid.memberId(), -paid.amountRwf(), Long::sum);
        }
        owedByMember.values().removeIf(owed -> owed <= 0);

        List<OwedMembersResponse.OwedMember> members = memberRepository.findAllById(owedByMember.keySet()).stream()
                .filter(member -> matches(member, search))
                .map(member -> new OwedMembersResponse.OwedMember(member.getId(), member.getFullName(),
                        member.getMemberCode(), member.getPhone(), owedByMember.get(member.getId())))
                .sorted(Comparator.comparingLong(OwedMembersResponse.OwedMember::owedRwf).reversed()
                        .thenComparing(OwedMembersResponse.OwedMember::memberName))
                .toList();
        long totalOwed = members.stream().mapToLong(OwedMembersResponse.OwedMember::owedRwf).sum();
        return new OwedMembersResponse(members, totalOwed);
    }

    private static boolean matches(Member member, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }
        String trimmed = search.trim();
        String term = trimmed.toLowerCase(Locale.ROOT);
        // Phones are stored as +2507XXXXXXXX, so a search for 07XXXXXXXX must be converted first.
        String phoneTerm = RwandanPhoneNumber.isValid(trimmed) ? RwandanPhoneNumber.normalize(trimmed) : term;
        return member.getFullName().toLowerCase(Locale.ROOT).contains(term)
                || member.getMemberCode().toLowerCase(Locale.ROOT).contains(term)
                || member.getPhone().contains(phoneTerm);
    }
}
