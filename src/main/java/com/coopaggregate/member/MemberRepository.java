package com.coopaggregate.member;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface MemberRepository extends JpaRepository<Member, Long>, JpaSpecificationExecutor<Member> {

    Optional<Member> findByMemberCode(String memberCode);

    Optional<Member> findByPhone(String phone);

    Optional<Member> findByNationalId(String nationalId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Member> findForUpdateById(Long id);

    // The number at the end of the code, whatever the prefix (M-0007 and MEM-0007 both give 7).
    @Query(value = "SELECT COALESCE(MAX(CAST(SUBSTRING(member_code FROM '[0-9]+$') AS INTEGER)), 0) FROM member",
            nativeQuery = true)
    int highestMemberNumber();

    long countByStatus(MemberStatus status);

    long countByJoinDateBetween(LocalDate from, LocalDate to);
}
