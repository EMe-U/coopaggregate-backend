package com.coopaggregate.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.common.PageResponse;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.member.Member;

class AuditServiceTest {

    @Test
    void historyShowsWhoChangedWhatNewestFirst() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        Manager manager = new Manager();
        manager.setName("Jeanne Manager");
        Member member = new Member();
        ReflectionTestUtils.setField(member, "id", 5L);
        member.setFullName("Alice Uwase");
        AuditLog settings = AuditLog.of(AuditAction.SETTINGS_UPDATED, null, manager, "smsEnabled: true -> false");
        AuditLog pin = AuditLog.of(AuditAction.PIN_RESET, member, manager, null);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(repository.findAllByOrderByIdDesc(pageable.capture())).thenReturn(new PageImpl<>(List.of(settings, pin)));

        PageResponse<AuditEntryResponse> history = new AuditService(repository).history(-1, 500);

        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(100, pageable.getValue().getPageSize());
        assertEquals("smsEnabled: true -> false", history.content().get(0).details());
        assertEquals("Jeanne Manager", history.content().get(0).managerName());
        assertNull(history.content().get(0).memberName());
        assertEquals(AuditAction.PIN_RESET, history.content().get(1).action());
        assertEquals(5L, history.content().get(1).memberId());
        assertEquals("Alice Uwase", history.content().get(1).memberName());
    }
}
