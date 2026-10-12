package com.coopaggregate.payment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.common.PageResponse;
import com.coopaggregate.common.RwandanPhoneNumber;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;

import jakarta.persistence.criteria.Predicate;

@Service
public class PaymentService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ProducePaymentRepository paymentRepository;
    private final MemberRepository memberRepository;
    private final LedgerService ledgerService;

    public PaymentService(ProducePaymentRepository paymentRepository, MemberRepository memberRepository,
                          LedgerService ledgerService) {
        this.paymentRepository = paymentRepository;
        this.memberRepository = memberRepository;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public RecordedPayment record(PaymentRequest request, Manager manager) {
        // The row lock makes payments to the same member wait for each other, so two payments cannot
        // both pass the owed check. It also makes a repeated client UUID wait for the first request.
        Member member = memberRepository.findForUpdateById(request.memberId())
                .orElseThrow(() -> new BadRequestException("Member " + request.memberId() + " does not exist."));

        Optional<ProducePayment> existing = paymentRepository.findByClientUuid(request.clientUuid());
        if (existing.isPresent()) {
            return new RecordedPayment(PaymentResponse.from(existing.get()), false);
        }

        String reference = request.reference() == null || request.reference().isBlank()
                ? null : request.reference().trim();
        if (request.method() != PaymentMethod.CASH && reference == null) {
            throw new BadRequestException("A " + request.method() + " payment needs the transaction reference.");
        }
        long owed = owedTo(member.getId());
        if (request.amountRwf() > owed) {
            throw new BadRequestException("Member " + member.getMemberCode() + " is owed " + owed
                    + " RWF. The payment cannot be more than that.");
        }

        ProducePayment payment = new ProducePayment();
        payment.setClientUuid(request.clientUuid());
        payment.setMember(member);
        payment.setAmount(request.amountRwf());
        payment.setMethod(request.method());
        payment.setReferenceNo(reference);
        payment.setPaymentDate(request.paidAt() != null ? request.paidAt() : KigaliTime.today());
        ProducePayment saved = paymentRepository.save(payment);

        ledgerService.record(LedgerEntryType.PAYMENT, "PAYMENT", saved.getId(), null, saved.getAmount(),
                "Payment by " + saved.getMethod() + (reference != null ? " " + reference : ""), manager);
        return new RecordedPayment(PaymentResponse.from(saved), true);
    }

    @Transactional(readOnly = true)
    public PageResponse<PaymentResponse> list(Long memberId, PaymentMethod method, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "paymentDate", "id"));
        return PageResponse.from(paymentRepository.findAll(matching(memberId, method), pageRequest)
                .map(PaymentResponse::from));
    }

    public long owedTo(Long memberId) {
        return paymentRepository.netSharesOf(memberId) - paymentRepository.paidTo(memberId);
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

    private static Specification<ProducePayment> matching(Long memberId, PaymentMethod method) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (memberId != null) {
                predicates.add(cb.equal(root.get("member").get("id"), memberId));
            }
            if (method != null) {
                predicates.add(cb.equal(root.get("method"), method));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
