package com.coopaggregate.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.persistence.EntityNotFoundException;

class MemberServiceTest {

    private MemberRepository repository;
    private MemberService service;

    @BeforeEach
    void setUp() {
        repository = mock(MemberRepository.class);
        service = new MemberService(repository);
        when(repository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.nextMemberCodeNumber()).thenReturn(7L);
        when(repository.findByPhone(any())).thenReturn(Optional.empty());
        when(repository.findByNationalId(any())).thenReturn(Optional.empty());
    }

    @Test
    void createGeneratesMemberCodeAndSavesDetails() {
        MemberResponse response = service.create(new MemberRequest(
                "  Uwimana Claudine ", "0788123456", "1199880012345678", "Busogo", LocalDate.of(2024, 3, 1), "en"));

        assertEquals("M-0007", response.memberCode());
        assertEquals("Uwimana Claudine", response.fullName());
        assertEquals("+250788123456", response.phone());
        assertEquals("1199880012345678", response.nationalId());
        assertEquals("Busogo", response.address());
        assertEquals(LocalDate.of(2024, 3, 1), response.joinDate());
        assertEquals(MemberStatus.ACTIVE, response.status());
        assertEquals("en", response.preferredLanguage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0788123456", "250788123456", "+250788123456"})
    void createStoresPhoneAsPlus250(String phone) {
        MemberResponse response = service.create(request(phone, null));

        assertEquals("+250788123456", response.phone());
    }

    @Test
    void createUsesDefaultsForOptionalFields() {
        MemberResponse response = service.create(new MemberRequest("Habimana Eric", "0722123456", " ", "", null, null));

        assertNull(response.nationalId());
        assertNull(response.address());
        assertEquals(LocalDate.now(), response.joinDate());
        assertEquals("rw", response.preferredLanguage());
    }

    @Test
    void createWithDuplicatePhoneIsRejected() {
        when(repository.findByPhone("+250788123456")).thenReturn(Optional.of(existingMember(3L, "M-0003")));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.create(request("0788123456", null)));

        assertEquals("Phone number +250788123456 is already used by member M-0003.", e.getMessage());
        verify(repository, never()).nextMemberCodeNumber();
        verify(repository, never()).save(any());
    }

    @Test
    void createWithDuplicateNationalIdIsRejected() {
        when(repository.findByNationalId("1199880012345678")).thenReturn(Optional.of(existingMember(4L, "M-0004")));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.create(request("0788123456", "1199880012345678")));

        assertEquals("National ID 1199880012345678 is already used by member M-0004.", e.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    void updateChangesDetailsButKeepsCodeAndJoinDate() {
        Member member = existingMember(5L, "M-0005");
        member.setJoinDate(LocalDate.of(2023, 1, 10));
        when(repository.findById(5L)).thenReturn(Optional.of(member));

        MemberResponse response = service.update(5L, request("+250733000111", null));

        assertEquals("M-0005", response.memberCode());
        assertEquals("+250733000111", response.phone());
        assertEquals(LocalDate.of(2023, 1, 10), response.joinDate());
    }

    @Test
    void updateKeepingOwnPhoneIsAllowed() {
        Member member = existingMember(5L, "M-0005");
        when(repository.findById(5L)).thenReturn(Optional.of(member));
        when(repository.findByPhone("+250788123456")).thenReturn(Optional.of(member));

        MemberResponse response = service.update(5L, request("0788123456", null));

        assertEquals("+250788123456", response.phone());
    }

    @Test
    void updateWithAnotherMembersPhoneIsRejected() {
        when(repository.findById(5L)).thenReturn(Optional.of(existingMember(5L, "M-0005")));
        when(repository.findByPhone("+250788123456")).thenReturn(Optional.of(existingMember(6L, "M-0006")));

        assertThrows(IllegalStateException.class, () -> service.update(5L, request("0788123456", null)));
    }

    @Test
    void getUnknownMemberIsNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        EntityNotFoundException e = assertThrows(EntityNotFoundException.class, () -> service.get(99L));

        assertEquals("Member 99 does not exist.", e.getMessage());
    }

    @Test
    void updateUnknownMemberIsNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.update(99L, request("0788123456", null)));
    }

    @Test
    void deactivateAndActivateChangeStatus() {
        Member member = existingMember(5L, "M-0005");
        when(repository.findById(5L)).thenReturn(Optional.of(member));

        assertEquals(MemberStatus.INACTIVE, service.setActive(5L, false).status());
        assertEquals(MemberStatus.ACTIVE, service.setActive(5L, true).status());
    }

    @Test
    void deactivateUnknownMemberIsNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.setActive(99L, false));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listCapsPageSizeAndSortsByName() {
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(repository.findAll(any(Specification.class), pageable.capture())).thenReturn(new PageImpl<>(List.of()));

        service.list("claudine", true, -1, 500);

        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(100, pageable.getValue().getPageSize());
        assertEquals("fullName: ASC,id: ASC", pageable.getValue().getSort().toString());
    }

    private static MemberRequest request(String phone, String nationalId) {
        return new MemberRequest("Uwimana Claudine", phone, nationalId, null, null, null);
    }

    private static Member existingMember(Long id, String memberCode) {
        Member member = new Member();
        ReflectionTestUtils.setField(member, "id", id);
        member.setMemberCode(memberCode);
        member.setFullName("Existing Member");
        member.setPhone("+250780000000");
        member.setJoinDate(LocalDate.of(2023, 1, 1));
        return member;
    }
}
