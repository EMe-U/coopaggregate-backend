package com.coopaggregate.payment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.lot.LotQuantities;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.member.MemberRepository;
import com.coopaggregate.share.LotShare;
import com.coopaggregate.share.LotShareRepository;
import com.coopaggregate.share.MemberLotKg;
import com.coopaggregate.share.ShareCalculator;

import jakarta.persistence.EntityNotFoundException;

@Service
public class StatementService {

    private final MemberRepository memberRepository;
    private final LotShareRepository lotShareRepository;
    private final LotRepository lotRepository;
    private final ProducePaymentRepository paymentRepository;

    public StatementService(MemberRepository memberRepository, LotShareRepository lotShareRepository,
                            LotRepository lotRepository, ProducePaymentRepository paymentRepository) {
        this.memberRepository = memberRepository;
        this.lotShareRepository = lotShareRepository;
        this.lotRepository = lotRepository;
        this.paymentRepository = paymentRepository;
    }

    @Transactional(readOnly = true)
    public StatementResponse statement(Long memberId) {
        if (!memberRepository.existsById(memberId)) {
            throw new EntityNotFoundException("Member " + memberId + " does not exist.");
        }

        Map<Long, LotShare> sharesByLot = lotShareRepository.findByMemberId(memberId).stream()
                .collect(Collectors.toMap(share -> share.getLot().getId(), Function.identity()));
        List<MemberLotKg> lots = lotShareRepository.findLotsOfMember(memberId);
        Map<Long, LotQuantities> lotTotals = lots.isEmpty() ? Map.of()
                : lotRepository.findQuantities(lots.stream().map(MemberLotKg::lotId).toList()).stream()
                        .collect(Collectors.toMap(LotQuantities::lotId, Function.identity()));

        List<StatementResponse.LotShareLine> lotShares = lots.stream()
                .map(lot -> lotLine(lot, sharesByLot.get(lot.lotId()), lotTotals.get(lot.lotId())))
                .toList();
        List<StatementResponse.PaymentLine> payments =
                paymentRepository.findByMemberIdOrderByPaymentDateDescIdDesc(memberId).stream()
                        .map(payment -> new StatementResponse.PaymentLine(payment.getPaymentDate(),
                                payment.getAmount(), payment.getMethod(), payment.getReferenceNo()))
                        .toList();

        long grossShare = sharesByLot.values().stream().mapToLong(LotShare::getShareAmount).sum();
        long deductions = sharesByLot.values().stream().mapToLong(LotShare::getDeductionAmount).sum();
        long paid = payments.stream().mapToLong(StatementResponse.PaymentLine::amountRwf).sum();
        return new StatementResponse(grossShare, deductions, paid, grossShare - deductions - paid, lotShares, payments);
    }

    // A shared lot shows the saved share; any other lot shows the current kilograms and no amount yet.
    private static StatementResponse.LotShareLine lotLine(MemberLotKg lot, LotShare share, LotQuantities totals) {
        if (share != null) {
            return new StatementResponse.LotShareLine(lot.lotId(), lot.lotCode(), lot.status(), share.getMemberKg(),
                    share.getLotKg(), ShareCalculator.percent(share.getMemberKg(), share.getLotKg()),
                    share.getShareAmount());
        }
        BigDecimal lotKg = totals != null ? totals.deliveredKg() : lot.kg();
        return new StatementResponse.LotShareLine(lot.lotId(), lot.lotCode(), lot.status(), lot.kg(), lotKg,
                ShareCalculator.percent(lot.kg(), lotKg), null);
    }
}
