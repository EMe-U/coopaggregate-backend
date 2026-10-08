package com.coopaggregate.member;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface MemberRepository extends JpaRepository<Member, Long>, JpaSpecificationExecutor<Member> {

    Optional<Member> findByMemberCode(String memberCode);

    Optional<Member> findByPhone(String phone);

    Optional<Member> findByNationalId(String nationalId);

    @Query(value = "SELECT nextval('member_code_seq')", nativeQuery = true)
    long nextMemberCodeNumber();
}
