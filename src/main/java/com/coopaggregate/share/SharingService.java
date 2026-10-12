package com.coopaggregate.share;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotQuantities;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.MemberRepository;

import jakarta.persistence.EntityNotFoundException;

@Service
public class SharingService {

    private final LotShareRepository lotShareRepository;
    private final LotRepository lotRepository;
    private final LotService lotService;
    private final MemberRepository memberRepository;
    private final LedgerService ledgerService;

    public SharingService(LotShareRepository lotShareRepository, LotRepository lotRepository, LotService lotService,
                          MemberRepository memberRepository, LedgerService ledgerService) {
        this.lotShareRepository = lotShareRepository;
        this.lotRepository = lotRepository;
        this.lotService = lotService;
        this.memberRepository = memberRepository;
        this.ledgerService = ledgerService;
    }

    @Transactional(readOnly = true)
    public SharePreviewResponse preview(Long lotId) {
        Lot lot = lotRepository.findById(lotId)
                .orElseThrow(() -> new EntityNotFoundException("Lot " + lotId + " does not exist."));
        checkCanBeShared(lot);
        return calculate(lot);
    }

    @Transactional
    public SharePreviewResponse share(Long lotId, Manager manager) {
        // The row lock makes a second share request for the same lot wait, then find it SHARED.
        Lot lot = lotRepository.findForUpdateById(lotId)
                .orElseThrow(() -> new EntityNotFoundException("Lot " + lotId + " does not exist."));
        checkCanBeShared(lot);

        SharePreviewResponse result = calculate(lot);
        for (SharePreviewResponse.MemberShare member : result.members()) {
            LotShare share = new LotShare();
            share.setLot(lot);
            share.setMember(memberRepository.getReferenceById(member.memberId()));
            share.setMemberKg(member.memberKg());
            share.setLotKg(result.deliveredKg());
            share.setShareAmount(member.shareRwf());
            share.setDeductionAmount(member.deductionRwf());
            LotShare saved = lotShareRepository.save(share);

            // The amount is the gross share; the deduction was already recorded with each delivery.
            ledgerService.record(LedgerEntryType.SHARE, "LOT_SHARE", saved.getId(), saved.getMemberKg(),
                    saved.getShareAmount(), "Share of " + lot.getLotCode(), manager);
        }
        lot.setStatus(LotStatus.SHARED);
        return result;
    }

    SharePreviewResponse calculate(Lot lot) {
        LotQuantities quantities = lotService.quantitiesOf(lot);
        List<MemberDelivered> delivered = lotShareRepository.findDeliveredByMember(lot.getId());
        List<Long> shares = ShareCalculator.split(quantities.saleAmountRwf(),
                delivered.stream().map(MemberDelivered::kg).toList());

        List<SharePreviewResponse.MemberShare> members = new ArrayList<>();
        long totalShare = 0;
        long totalDeduction = 0;
        for (int i = 0; i < delivered.size(); i++) {
            MemberDelivered member = delivered.get(i);
            long share = shares.get(i);
            long deduction = member.deductionRwf();
            members.add(new SharePreviewResponse.MemberShare(member.memberId(), member.memberName(),
                    member.memberNumber(), member.kg(), ShareCalculator.percent(member.kg(), quantities.deliveredKg()),
                    share, deduction, share - deduction));
            totalShare += share;
            totalDeduction += deduction;
        }

        return new SharePreviewResponse(lot.getLotCode(), lot.getGrade().getName(), quantities.deliveredKg(),
                quantities.saleAmountRwf(), members,
                new SharePreviewResponse.Totals(totalShare, totalDeduction, totalShare - totalDeduction));
    }

    private static void checkCanBeShared(Lot lot) {
        if (lot.getStatus() == LotStatus.SHARED) {
            throw new IllegalStateException("Lot " + lot.getLotCode() + " has already been shared.");
        }
        if (lot.getStatus() != LotStatus.SOLD) {
            throw new BadRequestException("Lot " + lot.getLotCode() + " is " + lot.getStatus()
                    + ". Only a sold lot can be shared.");
        }
    }
}
