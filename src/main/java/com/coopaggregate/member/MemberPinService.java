package com.coopaggregate.member;

import java.time.Instant;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.manager.Manager;

import jakarta.persistence.EntityNotFoundException;

/** Sets and checks the 4-digit PIN members use to confirm their deliveries. Only a BCrypt hash is stored. */
@Service
public class MemberPinService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogRepository auditLogRepository;

    public MemberPinService(MemberRepository memberRepository, PasswordEncoder passwordEncoder,
                            AuditLogRepository auditLogRepository) {
        this.memberRepository = memberRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogRepository = auditLogRepository;
    }

    /** Sets a first PIN or resets a forgotten or locked one. A reset clears the lock. */
    @Transactional
    public Member setPin(Long memberId, String pin, Manager manager) {
        PinPolicy.check(pin);
        Member member = memberRepository.findForUpdateById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("Member " + memberId + " does not exist."));

        AuditAction action = member.hasPin() ? AuditAction.PIN_RESET : AuditAction.PIN_SET;
        member.changePin(passwordEncoder.encode(pin), Instant.now());
        auditLogRepository.save(AuditLog.of(action, member, manager, null));
        return member;
    }
}
