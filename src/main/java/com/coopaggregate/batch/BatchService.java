package com.coopaggregate.batch;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.common.PageResponse;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.member.Member;
import com.coopaggregate.member.MemberRepository;

import jakarta.persistence.EntityNotFoundException;

/** Read-only views of the batches. Responses are built here, inside the transaction. */
@Service
public class BatchService {

    private static final int MAX_PAGE_SIZE = 100;

    private final BatchRepository batchRepository;
    private final MemberRepository memberRepository;
    private final GradeRepository gradeRepository;

    public BatchService(BatchRepository batchRepository, MemberRepository memberRepository,
                        GradeRepository gradeRepository) {
        this.batchRepository = batchRepository;
        this.memberRepository = memberRepository;
        this.gradeRepository = gradeRepository;
    }

    /** status is "all" (default) or "unsold" (only batches with kg left). */
    @Transactional(readOnly = true)
    public MemberBatchesResponse memberBatches(Long memberId, String status, int page, int size) {
        boolean unsoldOnly = unsoldOnly(status);
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("Member " + memberId + " does not exist."));
        return new MemberBatchesResponse(member.getId(), member.getMemberCode(), member.getFullName(),
                batchRepository.memberTotals(memberId),
                PageResponse.from(batchRepository.findMemberBatches(memberId, unsoldOnly, pageOf(page, size))
                        .map(BatchResponse::from)));
    }

    @Transactional(readOnly = true)
    public GradeBatchesResponse gradeBatches(Long gradeId, int page, int size) {
        Grade grade = gradeRepository.findById(gradeId)
                .orElseThrow(() -> new EntityNotFoundException("Grade " + gradeId + " does not exist."));
        return new GradeBatchesResponse(grade.getId(), grade.getName(), batchRepository.gradeRemainingKg(gradeId),
                PageResponse.from(batchRepository.findGradeBatchesWithKgLeft(gradeId, pageOf(page, size))
                        .map(BatchResponse::from)));
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
