package com.coopaggregate.batch;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.common.PageResponse;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;
import com.coopaggregate.share.LotShareRepository;
import com.coopaggregate.share.MemberLot;

import jakarta.persistence.EntityNotFoundException;

/** Read-only views of the batches. Responses are built here, inside the transaction. */
@Service
public class BatchService {

    private static final int MAX_PAGE_SIZE = 100;

    private final BatchRepository batchRepository;
    private final MemberRepository memberRepository;
    private final GradeRepository gradeRepository;
    private final SaleAllocationRepository saleAllocationRepository;
    private final LotShareRepository lotShareRepository;

    public BatchService(BatchRepository batchRepository, MemberRepository memberRepository,
                        GradeRepository gradeRepository, SaleAllocationRepository saleAllocationRepository,
                        LotShareRepository lotShareRepository) {
        this.batchRepository = batchRepository;
        this.memberRepository = memberRepository;
        this.gradeRepository = gradeRepository;
        this.saleAllocationRepository = saleAllocationRepository;
        this.lotShareRepository = lotShareRepository;
    }

    /** status is "all" (default) or "unsold" (only batches with kg left). */
    @Transactional(readOnly = true)
    public MemberBatchesResponse memberBatches(Long memberId, String status, int page, int size) {
        boolean unsoldOnly = unsoldOnly(status);
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("Member " + memberId + " does not exist."));
        return new MemberBatchesResponse(member.getId(), member.getMemberCode(), member.getFullName(),
                batchRepository.memberTotals(memberId),
                saleAllocationRepository.memberEarnings(memberId),
                withMoney(batchRepository.findMemberBatches(memberId, unsoldOnly, pageOf(page, size))));
    }

    @Transactional(readOnly = true)
    public GradeBatchesResponse gradeBatches(Long gradeId, int page, int size) {
        Grade grade = gradeRepository.findById(gradeId)
                .orElseThrow(() -> new EntityNotFoundException("Grade " + gradeId + " does not exist."));
        return new GradeBatchesResponse(grade.getId(), grade.getName(), batchRepository.gradeRemainingKg(gradeId),
                withMoney(batchRepository.findGradeBatchesWithKgLeft(gradeId, pageOf(page, size))));
    }

    // Two queries for the whole page: the credits of its batches, and which members have a share of their lots.
    private PageResponse<BatchResponse> withMoney(Page<Delivery> batches) {
        List<Delivery> content = batches.getContent();
        if (content.isEmpty()) {
            return PageResponse.from(batches.map(batch -> BatchResponse.from(batch, null, false)));
        }
        Map<Long, BatchCredit> credits = saleAllocationRepository
                .findCreditsOfBatches(content.stream().map(Delivery::getId).toList()).stream()
                .collect(Collectors.toMap(BatchCredit::deliveryId, Function.identity()));
        Set<MemberLot> shared = new HashSet<>(lotShareRepository
                .findSharedMemberLots(content.stream().map(batch -> batch.getLot().getId()).distinct().toList()));
        return PageResponse.from(batches.map(batch -> BatchResponse.from(batch, credits.get(batch.getId()),
                shared.contains(new MemberLot(batch.getMember().getId(), batch.getLot().getId())))));
    }

    private static PageRequest pageOf(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    private static boolean unsoldOnly(String status) {
        if (status == null || status.isBlank() || status.trim().equalsIgnoreCase("all")) {
            return false;
        }
        if (status.trim().equalsIgnoreCase("unsold")) {
            return true;
        }
        throw new BadRequestException("status must be all or unsold.");
    }
}
