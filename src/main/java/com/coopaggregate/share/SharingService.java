package com.coopaggregate.share;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotQuantities;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.LotStatus;

import jakarta.persistence.EntityNotFoundException;

@Service
public class SharingService {

    private final LotShareRepository lotShareRepository;
    private final LotRepository lotRepository;
    private final LotService lotService;

    public SharingService(LotShareRepository lotShareRepository, LotRepository lotRepository, LotService lotService) {
        this.lotShareRepository = lotShareRepository;
        this.lotRepository = lotRepository;
        this.lotService = lotService;
    }

    @Transactional(readOnly = true)
    public SharePreviewResponse preview(Long lotId) {
        Lot lot = lotRepository.findById(lotId)
                .orElseThrow(() -> new EntityNotFoundException("Lot " + lotId + " does not exist."));
        checkCanBeShared(lot);
        return calculate(lot);
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
