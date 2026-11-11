package com.coopaggregate.member;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.audit.AuditAction;
import com.coopaggregate.audit.AuditLog;
import com.coopaggregate.audit.AuditLogRepository;
import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.identity.IdentityAvailability;
import com.coopaggregate.identity.DuplicateIdentityException;
import com.coopaggregate.identity.IdentityProviderException;
import com.coopaggregate.identity.IdentityVerificationService;
import com.coopaggregate.identity.VerificationMethod;
import com.coopaggregate.identity.VerifiedDetails;
import com.coopaggregate.manager.Manager;

import jakarta.persistence.EntityNotFoundException;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;

class MemberServiceTest {

    private MemberRepository repository;
    private MemberService service;
    private IdentityAvailability identityAvailability;
    private IdentityVerificationService identityVerificationService;
    private AuditLogRepository auditLogRepository;
    private final Manager manager = new Manager();
    private static final String ID = "1199880012345678";

    @BeforeEach
    void setUp() {
        repository = mock(MemberRepository.class);
        identityAvailability = mock(IdentityAvailability.class);
        identityVerificationService = mock(IdentityVerificationService.class);
        when(identityAvailability.isAvailable()).thenReturn(true);
        auditLogRepository = mock(AuditLogRepository.class);
        service = new MemberService(repository, identityAvailability, identityVerificationService, auditLogRepository);
        when(repository.save(any(Member.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(repository.highestMemberNumber()).thenReturn(7);
        when(repository.findByPhone(any())).thenReturn(Optional.empty());
        when(repository.findByNationalId(any())).thenReturn(Optional.empty());
        verifiedAs("  Uwimana Claudine ", "+250788123456");
    }

    // The verification 9 of this manager verified this person.
    private void verifiedAs(String fullName, String phone) {
        when(identityVerificationService.verifiedDetails(9L, manager)).thenReturn(new VerifiedDetails(fullName, phone));
    }

    @Test
    void createGeneratesMemberCodeAndSavesDetails() {
        MemberResponse response = service.create(new NewMemberRequest(9L, null, null, "1199880012345678", "Busogo",
                LocalDate.of(2024, 3, 1), "en"), manager);

        assertEquals("M-0008", response.memberCode());
        assertEquals("Uwimana Claudine", response.fullName());
        assertEquals("+250788123456", response.phone());
        assertEquals("1199880012345678", response.nationalId());
        assertEquals("Busogo", response.address());
        assertEquals(LocalDate.of(2024, 3, 1), response.joinDate());
        assertEquals(MemberStatus.ACTIVE, response.status());
        assertEquals("en", response.preferredLanguage());
    }

    @Test
    void firstMemberGetsNumberOne() {
        when(repository.highestMemberNumber()).thenReturn(0);

        assertEquals("M-0001", service.create(registration(ID), manager).memberCode());
    }

    @Test
    void theNameAndPhoneAreTheVerifiedOnes() {
        verifiedAs("Habimana Eric", "+250722123456");

        MemberResponse response = service.create(registration(ID), manager);

        assertEquals("Habimana Eric", response.fullName());
        assertEquals("+250722123456", response.phone());
    }

    @Test
    void createUsesDefaultsForOptionalFields() {
        MemberResponse response = service.create(new NewMemberRequest(9L, null, null, ID, "", null, null), manager);

        assertNull(response.address());
        assertEquals(KigaliTime.today(), response.joinDate());
        assertEquals("rw", response.preferredLanguage());
    }

    @Test
    void createWithDuplicatePhoneIsRejected() {
        when(repository.findByPhone("+250788123456")).thenReturn(Optional.of(existingMember(3L, "M-0003")));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.create(registration(ID), manager));

        assertEquals("Phone number +250788123456 is already used by member M-0003.", e.getMessage());
        verify(repository, never()).highestMemberNumber();
        verify(repository, never()).save(any());
        verify(identityVerificationService, never()).attach(any(), any(), any());
    }

    @Test
    void createWithDuplicateNationalIdIsRejected() {
        when(repository.findByNationalId("1199880012345678")).thenReturn(Optional.of(existingMember(4L, "M-0004")));

        NationalIdTakenException e = assertThrows(NationalIdTakenException.class,
                () -> service.create(registration("1199880012345678"), manager));

        assertEquals("NATIONAL_ID_TAKEN", e.errorCode());
        assertEquals("M-0004", e.memberCode());
        assertEquals("This national ID is already used by member M-0004.", e.getMessage());
        verify(repository, never()).save(any());
        verify(identityVerificationService, never()).attach(any(), any(), any());
    }

    // --- National ID: required for a new member, exactly 16 digits, unique

    @Test
    void aNewMemberWithoutANationalIdIsRefused() {
        for (String missing : Arrays.asList(null, "", "   ")) {
            NationalIdException e = assertThrows(NationalIdException.class,
                    () -> service.create(registration(missing), manager));

            assertEquals("NATIONAL_ID_REQUIRED", e.errorCode());
            assertEquals("nationalId: is required", e.getMessage());
        }
        verify(repository, never()).save(any());
        verify(identityVerificationService, never()).attach(any(), any(), any());
    }

    @Test
    void aNationalIdOfTheWrongLengthIsRefused() {
        for (String wrong : List.of("119988001234567", "11998800123456789", "1")) {
            NationalIdException e = assertThrows(NationalIdException.class,
                    () -> service.create(registration(wrong), manager));

            assertEquals("NATIONAL_ID_INVALID", e.errorCode());
            assertEquals("nationalId: must be exactly 16 digits", e.getMessage());
        }
        verify(repository, never()).save(any());
    }

    @Test
    void aNationalIdWithLettersOrOtherCharactersIsRefused() {
        for (String wrong : List.of("119988001234567A", "1199 8800 1234 5678", "1199-880012345678",
                "١١٩٩٨٨٠٠١٢٣٤٥٦٧٨", "+199880012345678")) {
            NationalIdException e = assertThrows(NationalIdException.class,
                    () -> service.create(registration(wrong), manager), wrong);

            assertEquals("NATIONAL_ID_INVALID", e.errorCode(), wrong);
            assertFalse(e.getMessage().contains(wrong), "the message never repeats the value");
        }
        verify(repository, never()).save(any());
    }

    @Test
    void theSpacesAroundANationalIdAreTrimmed() {
        MemberResponse response = service.create(registration("  1199880012345678 "), manager);

        assertEquals("1199880012345678", response.nationalId());
    }

    @Test
    void theUnverifiedPathNeedsAValidNationalIdToo() {
        when(identityAvailability.isAvailable()).thenReturn(false);
        when(identityAvailability.isUnverifiedAllowed()).thenReturn(true);

        assertEquals("NATIONAL_ID_REQUIRED", assertThrows(NationalIdException.class, () -> service.create(
                new NewMemberRequest(null, "Mukamana Alice", "0788123456", null, null, null, null), manager))
                .errorCode());
        assertEquals("NATIONAL_ID_INVALID", assertThrows(NationalIdException.class, () -> service.create(
                new NewMemberRequest(null, "Mukamana Alice", "0788123456", "12345", null, null, null), manager))
                .errorCode());
        when(repository.findByNationalId(ID)).thenReturn(Optional.of(existingMember(4L, "M-0004")));
        assertEquals("M-0004", assertThrows(NationalIdTakenException.class, () -> service.create(
                new NewMemberRequest(null, "Mukamana Alice", "0788123456", ID, null, null, null), manager))
                .memberCode());
        verify(repository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void theTakenAnswerNamesOnlyTheOtherMembersCode() {
        Member other = existingMember(4L, "M-0004");
        other.setNationalId(ID);
        when(repository.findByNationalId(ID)).thenReturn(Optional.of(other));

        NationalIdTakenException e = assertThrows(NationalIdTakenException.class,
                () -> service.create(registration(ID), manager));

        for (String detail : List.of(ID, "Existing Member", "780000000")) {
            assertFalse(e.getMessage().contains(detail), detail);
        }
    }

    // --- National ID on edit

    @Test
    void anOldMemberWithoutANationalIdStillLoadsAndCanBeEditedWithoutOne() {
        Member old = existingMember(5L, "M-0005");
        when(repository.findById(5L)).thenReturn(Optional.of(old));

        assertNull(service.get(5L).nationalId());
        MemberResponse response = service.update(5L, request("0722123456", null));

        assertNull(response.nationalId());
        assertEquals("+250722123456", response.phone());
        verify(repository, never()).findByNationalId(any());
    }

    @Test
    void anEditMayAddANationalIdToAnOldMember() {
        Member old = existingMember(5L, "M-0005");
        when(repository.findById(5L)).thenReturn(Optional.of(old));

        assertEquals(ID, service.update(5L, request("0722123456", " " + ID + " ")).nationalId());
    }

    @Test
    void anEditCanNeverRemoveANationalIdThatIsSet() {
        Member member = existingMember(5L, "M-0005");
        member.setNationalId(ID);
        when(repository.findById(5L)).thenReturn(Optional.of(member));

        for (String empty : Arrays.asList(null, "", "  ")) {
            NationalIdException e = assertThrows(NationalIdException.class,
                    () -> service.update(5L, request("0722123456", empty)));

            assertEquals("NATIONAL_ID_REQUIRED", e.errorCode());
            assertEquals("nationalId: cannot be removed once it is set", e.getMessage());
        }
        assertEquals(ID, member.getNationalId());
        assertEquals("+250780000000", member.getPhone(), "nothing changed");
    }

    @Test
    void anEditChecksANewNationalIdLikeACreate() {
        Member member = existingMember(5L, "M-0005");
        when(repository.findById(5L)).thenReturn(Optional.of(member));

        assertEquals("NATIONAL_ID_INVALID", assertThrows(NationalIdException.class,
                () -> service.update(5L, request("0722123456", "12345678901234AB"))).errorCode());
        when(repository.findByNationalId(ID)).thenReturn(Optional.of(existingMember(4L, "M-0004")));
        assertEquals("M-0004", assertThrows(NationalIdTakenException.class,
                () -> service.update(5L, request("0722123456", ID))).memberCode());
        assertNull(member.getNationalId());
    }

    @Test
    void anEditKeepingTheMembersOwnNationalIdIsNotADuplicate() {
        Member member = existingMember(5L, "M-0005");
        member.setNationalId(ID);
        when(repository.findById(5L)).thenReturn(Optional.of(member));
        when(repository.findByNationalId(ID)).thenReturn(Optional.of(member));

        assertEquals(ID, service.update(5L, request("0722123456", ID)).nationalId());
    }

    @Test
    void anOlderNationalIdInAnotherFormatSurvivesAnEditThatSendsItBackUnchanged() {
        Member member = existingMember(5L, "M-0005");
        member.setNationalId("RW-12345");
        when(repository.findById(5L)).thenReturn(Optional.of(member));

        assertEquals("RW-12345", service.update(5L, request("0722123456", "RW-12345")).nationalId());
        assertEquals("NATIONAL_ID_INVALID", assertThrows(NationalIdException.class,
                () -> service.update(5L, request("0722123456", "RW-99999"))).errorCode());
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

    @Test
    @SuppressWarnings("unchecked")
    void listLeavesOutTheNationalId() {
        Member member = new Member();
        member.setFullName("Uwimana Claudine");
        member.setNationalId("1199880012345678");
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(member)));

        MemberListItem item = service.list(null, null, 0, 20).content().get(0);

        assertEquals("Uwimana Claudine", item.fullName());
        assertTrue(Arrays.stream(MemberListItem.class.getRecordComponents())
                .noneMatch(component -> component.getName().equals("nationalId")));
    }

    @Test
    void singleMemberStillShowsTheNationalId() {
        Member member = new Member();
        member.setNationalId("1199880012345678");
        when(repository.findById(5L)).thenReturn(Optional.of(member));

        assertEquals("1199880012345678", service.get(5L).nationalId());
    }

    // --- Identity verification

    @Test
    void aMemberCannotBeRegisteredWithoutAVerification() {
        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.create(new NewMemberRequest(null, null, null, null, null, null, null), manager));

        assertEquals("verificationId is required. Verify the person's identity first.", e.getMessage());
        verify(repository, never()).save(any());
        verify(identityVerificationService, never()).verifiedDetails(any(), any());
    }

    @Test
    void whenIdentityVerificationIsNotConfiguredNoMemberCanBeRegistered() {
        when(identityAvailability.isAvailable()).thenReturn(false);

        IdentityProviderException e = assertThrows(IdentityProviderException.class,
                () -> service.create(registration(ID), manager));

        assertTrue(e.isNotConfigured());
        assertEquals("Member registration needs identity verification, which is not configured.", e.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    void whenIdentityVerificationIsNotConfiguredTheMessageComesBeforeAnyOtherCheck() {
        when(identityAvailability.isAvailable()).thenReturn(false);

        assertThrows(IdentityProviderException.class,
                () -> service.create(new NewMemberRequest(null, null, null, null, null, null, null), manager));
    }

    // --- MEMBER_REGISTER_UNVERIFIED (identity verification off)

    private void unverifiedAllowed() {
        when(identityAvailability.isAvailable()).thenReturn(false);
        when(identityAvailability.isUnverifiedAllowed()).thenReturn(true);
    }

    private static NewMemberRequest typed(String fullName, String phone) {
        return new NewMemberRequest(null, fullName, phone, "1199880012345678", "Busogo", LocalDate.of(2024, 3, 1),
                "en");
    }

    @Test
    void withUnverifiedAllowedTheTypedNameAndPhoneAreStoredAsNotVerified() {
        unverifiedAllowed();

        MemberResponse response = service.create(typed("  Mukamana Alice ", "0788123456"), manager);

        assertEquals("M-0008", response.memberCode());
        assertEquals("Mukamana Alice", response.fullName());
        assertEquals("+250788123456", response.phone());
        assertEquals("1199880012345678", response.nationalId());
        assertEquals("Busogo", response.address());
        assertEquals(LocalDate.of(2024, 3, 1), response.joinDate());
        assertFalse(response.verified());
        assertNull(response.verificationMethod());
        assertNull(response.verifiedAt());
        assertEquals(List.of(), response.verifiedFields());
        verify(identityVerificationService, never()).verifiedDetails(any(), any());
        verify(identityVerificationService, never()).attach(any(), any(), any());
    }

    @Test
    void anUnverifiedRegistrationWritesAnAuditRow() {
        unverifiedAllowed();

        service.create(typed("Mukamana Alice", "0788123456"), manager);

        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(audit.capture());
        assertEquals(AuditAction.MEMBER_REGISTERED_UNVERIFIED, audit.getValue().getAction());
        assertEquals("registered without identity verification", audit.getValue().getDetails());
        assertEquals("M-0008", audit.getValue().getMember().getMemberCode());
    }

    @Test
    void anUnverifiedRegistrationNeedsTheNameAndPhoneWithTheRulesOfEditing() {
        unverifiedAllowed();

        assertEquals("fullName: is required", assertThrows(BadRequestException.class,
                () -> service.create(typed(null, "0788123456"), manager)).getMessage());
        assertEquals("fullName: is required", assertThrows(BadRequestException.class,
                () -> service.create(typed("   ", "0788123456"), manager)).getMessage());
        assertEquals("fullName: must be at most 150 characters", assertThrows(BadRequestException.class,
                () -> service.create(typed("a".repeat(151), "0788123456"), manager)).getMessage());
        assertEquals("phone: is required", assertThrows(BadRequestException.class,
                () -> service.create(typed("Mukamana Alice", " "), manager)).getMessage());
        assertTrue(assertThrows(BadRequestException.class,
                () -> service.create(typed("Mukamana Alice", "12345"), manager)).getMessage()
                .startsWith("phone: must be a Rwandan mobile number"));
        verify(repository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void anUnverifiedRegistrationRefusesTheDetailsThatOnlyComeFromTheCheck() {
        unverifiedAllowed();

        for (NewMemberRequest request : List.of(
                new NewMemberRequest(null, "Mukamana Alice", "0788123456", null, null, null, null,
                        LocalDate.of(1990, 5, 17), null, null),
                new NewMemberRequest(null, "Mukamana Alice", "0788123456", null, null, null, null, null, "Female",
                        null),
                new NewMemberRequest(null, "Mukamana Alice", "0788123456", null, null, null, null, null, null,
                        "alice@example.com"))) {
            BadRequestException e = assertThrows(BadRequestException.class, () -> service.create(request, manager));
            assertTrue(e.getMessage().endsWith(": must not be sent: it only comes from the identity check"),
                    e.getMessage());
        }
        verify(repository, never()).save(any());
    }

    @Test
    void anUnverifiedRegistrationRefusesAVerificationId() {
        unverifiedAllowed();

        BadRequestException e = assertThrows(BadRequestException.class, () -> service.create(
                new NewMemberRequest(9L, "Mukamana Alice", "0788123456", null, null, null, null), manager));

        assertEquals("verificationId: must not be sent: identity verification is switched off", e.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    void anUnverifiedRegistrationStillRefusesAPhoneAlreadyUsed() {
        unverifiedAllowed();
        when(repository.findByPhone("+250788123456")).thenReturn(Optional.of(existingMember(3L, "M-0003")));

        assertThrows(IllegalStateException.class,
                () -> service.create(typed("Mukamana Alice", "0788123456"), manager));
        verify(repository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void whileVerificationIsOnTheUnverifiedPathIsNeverTaken() {
        when(identityAvailability.isUnverifiedAllowed()).thenReturn(true);

        BadRequestException e = assertThrows(BadRequestException.class,
                () -> service.create(typed("Mukamana Alice", "0788123456"), manager));

        assertEquals("verificationId is required. Verify the person's identity first.", e.getMessage());
        verify(repository, never()).save(any());
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void theVerificationIsReadBeforeTheMemberIsSavedAndAttachedAfter() {
        doAnswerMarkVerified();

        MemberResponse response = service.create(registration(ID), manager);

        InOrder order = inOrder(identityVerificationService, repository);
        order.verify(identityVerificationService).verifiedDetails(9L, manager);
        order.verify(repository).save(any(Member.class));
        order.verify(identityVerificationService).attach(eq(9L), eq(manager), any(Member.class));
        assertTrue(response.verified());
        assertEquals(VerificationMethod.PASSWORD, response.verificationMethod());
        assertEquals(Instant.parse("2026-10-22T08:00:00Z"), response.verifiedAt());
    }

    @Test
    void aVerificationThatCannotBeUsedSavesNothing() {
        when(identityVerificationService.verifiedDetails(9L, manager))
                .thenThrow(new BadRequestException("Unknown verification. Verify the person again."));

        assertThrows(BadRequestException.class, () -> service.create(registration(ID), manager));

        verify(repository, never()).save(any());
        verify(identityVerificationService, never()).attach(any(), any(), any());
    }

    @Test
    void aPersonWhoBecameAMemberInTheMeantimeIsRefused() {
        org.mockito.Mockito.doThrow(new DuplicateIdentityException()).when(identityVerificationService)
                .attach(eq(9L), eq(manager), any(Member.class));

        assertThrows(DuplicateIdentityException.class, () -> service.create(registration(ID), manager));
    }

    // --- Every verified detail

    private static final VerifiedDetails ALL = new VerifiedDetails("Uwimana Claudine", "+250788123456",
            LocalDate.of(1990, 5, 17), "Female", "claudine@example.com", "KG 7 Ave, Kacyiru, Gasabo, Rwanda");

    @Test
    void everyVerifiedDetailComesFromTheVerificationAndIsMarkedVerified() {
        when(identityVerificationService.verifiedDetails(9L, manager)).thenReturn(ALL);
        doAnswerMarkVerified();

        MemberResponse response = service.create(registration("1199880012345678"), manager);

        assertEquals("Uwimana Claudine", response.fullName());
        assertEquals("+250788123456", response.phone());
        assertEquals(LocalDate.of(1990, 5, 17), response.birthDate());
        assertEquals("Female", response.gender());
        assertEquals("claudine@example.com", response.email());
        assertEquals("KG 7 Ave, Kacyiru, Gasabo, Rwanda", response.address());
        assertEquals("1199880012345678", response.nationalId(), "the national ID stays typed by the manager");
        assertEquals(List.of("fullName", "phone", "birthdate", "gender", "email", "address"),
                response.verifiedFields());
        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(repository).save(saved.capture());
        assertTrue(saved.getValue().isBirthDateVerified());
        assertTrue(saved.getValue().isGenderVerified());
        assertTrue(saved.getValue().isEmailVerified());
        assertTrue(saved.getValue().isAddressVerified());
    }

    @Test
    void detailsThatWereNotVerifiedStayEmptyAndUnflagged() {
        when(identityVerificationService.verifiedDetails(9L, manager)).thenReturn(new VerifiedDetails(
                "Uwimana Claudine", "+250788123456", null, "Female", null, null));
        doAnswerMarkVerified();

        MemberResponse response = service.create(new NewMemberRequest(9L, null, null, ID, "Busogo", null, null),
                manager);

        assertNull(response.birthDate());
        assertNull(response.email());
        assertEquals("Busogo", response.address(), "an address that was not verified is typed as before");
        assertEquals(List.of("fullName", "phone", "gender"), response.verifiedFields());
    }

    @Test
    void aValueSentForAnyVerifiedFieldIsRefusedWithoutSavingAnything() {
        when(identityVerificationService.verifiedDetails(9L, manager)).thenReturn(ALL);
        List<NewMemberRequest> overrides = List.of(
                new NewMemberRequest(9L, "Someone Else", null, null, null, null, null),
                new NewMemberRequest(9L, null, "0788999999", null, null, null, null),
                new NewMemberRequest(9L, null, null, null, null, null, null, LocalDate.of(1991, 1, 1), null, null),
                new NewMemberRequest(9L, null, null, null, null, null, null, null, "Male", null),
                new NewMemberRequest(9L, null, null, null, null, null, null, null, null, "other@example.com"),
                new NewMemberRequest(9L, null, null, null, "Musanze", null, null),
                new NewMemberRequest(9L, "", null, null, null, null, null),
                new NewMemberRequest(9L, null, null, null, "", null, null));

        for (NewMemberRequest request : overrides) {
            com.coopaggregate.identity.IdentityRequestException e = assertThrows(
                    com.coopaggregate.identity.IdentityRequestException.class, () -> service.create(request, manager));

            assertEquals("VERIFIED_FIELD_LOCKED", e.errorCode());
            assertEquals("This detail came from the identity check and cannot be changed here.", e.getMessage());
        }
        verify(repository, never()).save(any());
        verify(identityVerificationService, never()).attach(any(), any(), any());
    }

    @Test
    void theRefusalNeverRepeatsTheVerifiedValue() {
        when(identityVerificationService.verifiedDetails(9L, manager)).thenReturn(ALL);

        String message = assertThrows(BadRequestException.class, () -> service.create(new NewMemberRequest(9L,
                null, null, null, null, null, null, null, null, "other@example.com"), manager)).getMessage();

        for (String value : List.of("claudine", "1990", "Female", "Kacyiru", "Uwimana", "788123456", "other@")) {
            assertFalse(message.contains(value), value);
        }
    }

    @Test
    void theNameAndPhoneAreLockedEvenWithoutOptionalDetails() {
        BadRequestException e = assertThrows(BadRequestException.class, () -> service.create(
                new NewMemberRequest(9L, "Someone Else", "0788999999", null, null, null, null), manager));

        assertEquals("VERIFIED_FIELD_LOCKED", ((com.coopaggregate.identity.IdentityRequestException) e).errorCode());
    }

    @Test
    void aBirthDateGenderOrEmailThatWasNotVerifiedCannotBeTypedEither() {
        BadRequestException birth = assertThrows(BadRequestException.class, () -> service.create(new NewMemberRequest(
                9L, null, null, null, null, null, null, LocalDate.of(1990, 5, 17), null, null), manager));
        BadRequestException email = assertThrows(BadRequestException.class, () -> service.create(new NewMemberRequest(
                9L, null, null, null, null, null, null, null, null, "x@example.com"), manager));

        assertEquals("birthDate: must not be sent: it only comes from the identity check", birth.getMessage());
        assertEquals("email: must not be sent: it only comes from the identity check", email.getMessage());
        verify(repository, never()).save(any());
    }

    @Test
    void changingAVerifiedAddressRemovesItsVerifiedMark() {
        Member member = existingMember(5L, "M-0005");
        member.markIdentityVerified(VerificationMethod.PASSWORD, Instant.now(), "sub");
        member.setVerifiedAddress("KG 7 Ave, Kacyiru");
        when(repository.findById(5L)).thenReturn(Optional.of(member));

        service.update(5L, new MemberRequest("Existing Member", "+250780000000", null, "KG 7 Ave, Kacyiru", null,
                null));
        assertTrue(member.isAddressVerified(), "unchanged");

        MemberResponse response = service.update(5L, new MemberRequest("Existing Member", "+250780000000", null,
                "Musanze", null, null));
        assertFalse(member.isAddressVerified());
        assertEquals(List.of("fullName", "phone"), response.verifiedFields());
    }

    @Test
    void aMemberWhoWasNeverVerifiedHasNoVerifiedFields() {
        assertEquals(List.of(), MemberResponse.from(existingMember(5L, "M-0005")).verifiedFields());
    }

    @Test
    void theMemberRequestsNeverPrintTheirDetails() {
        String created = new NewMemberRequest(9L, null, null, "1199880012345678", "Busogo", null, "rw").toString();
        String updated = request("0722123456", "1199880012345678").toString();

        for (String text : List.of(created, updated)) {
            assertFalse(text.contains("1199880012345678"), text);
            assertFalse(text.contains("Busogo"), text);
            assertFalse(text.contains("722123456"), text);
            assertFalse(text.contains("Uwimana"), text);
        }
    }

    @Test
    void updatingAMemberNeverTouchesTheVerification() {
        Member member = new Member();
        ReflectionTestUtils.setField(member, "id", 3L);
        when(repository.findById(3L)).thenReturn(Optional.of(member));

        service.update(3L, request("0722123456", null));

        verify(identityVerificationService, never()).verifiedDetails(any(), any());
        verify(identityVerificationService, never()).attach(any(), any(), any());
        assertFalse(member.isIdentityVerified());
    }

    // --- A national ID released by the identity check (MOSIP_NATIONAL_ID_CLAIM)

    private static final String UIN = "5860356276";

    private void verifiedWithNationalId(String nationalId) {
        when(identityVerificationService.verifiedDetails(9L, manager)).thenReturn(new VerifiedDetails(
                "Uwimana Claudine", "+250788123456", null, null, null, null, nationalId));
    }

    @Test
    void aReleasedNationalIdIsStoredWithoutTheSixteenDigitRule() {
        verifiedWithNationalId(UIN);
        doAnswerMarkVerified();

        MemberResponse response = service.create(registration(null), manager);

        assertEquals(UIN, response.nationalId(), "the 10-digit UIN, not held to the 16 digits of a typed ID");
        ArgumentCaptor<Member> saved = ArgumentCaptor.forClass(Member.class);
        verify(repository).save(saved.capture());
        assertEquals(UIN, saved.getValue().getNationalId());
        verify(repository).findByNationalId(UIN);
        verify(identityVerificationService).attach(eq(9L), eq(manager), any(Member.class));
    }

    @Test
    void aNationalIdSentWhenTheCheckReleasedOneIsRefusedWithoutSavingAnything() {
        verifiedWithNationalId(UIN);

        for (String sent : List.of(ID, UIN, "", "  ")) {
            com.coopaggregate.identity.IdentityRequestException e = assertThrows(
                    com.coopaggregate.identity.IdentityRequestException.class,
                    () -> service.create(registration(sent), manager));

            assertEquals("VERIFIED_FIELD_LOCKED", e.errorCode());
            assertEquals("This detail came from the identity check and cannot be changed here.", e.getMessage());
        }
        verify(repository, never()).save(any());
        verify(identityVerificationService, never()).attach(any(), any(), any());
    }

    @Test
    void aReleasedNationalIdAlreadyUsedIsRefusedWithTheOtherMembersCodeOnly() {
        verifiedWithNationalId(UIN);
        when(repository.findByNationalId(UIN)).thenReturn(Optional.of(existingMember(4L, "M-0004")));

        NationalIdTakenException e = assertThrows(NationalIdTakenException.class,
                () -> service.create(registration(null), manager));

        assertEquals("NATIONAL_ID_TAKEN", e.errorCode());
        assertEquals("M-0004", e.memberCode());
        assertFalse(e.getMessage().contains(UIN));
        verify(repository, never()).save(any());
        verify(identityVerificationService, never()).attach(any(), any(), any());
    }

    @Test
    void withoutAReleasedNationalIdTheTypedOneIsRequiredWithTheSixteenDigitRule() {
        verifiedWithNationalId(null);

        assertEquals("NATIONAL_ID_REQUIRED", assertThrows(NationalIdException.class,
                () -> service.create(registration(null), manager)).errorCode());
        assertEquals("NATIONAL_ID_INVALID", assertThrows(NationalIdException.class,
                () -> service.create(registration(UIN), manager)).errorCode());
        verify(repository, never()).save(any());

        assertEquals(ID, service.create(registration(ID), manager).nationalId());
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void aReleasedNationalIdIsNeverInAnErrorOrALog(org.springframework.boot.test.system.CapturedOutput output) {
        verifiedWithNationalId(UIN);
        List<String> messages = new java.util.ArrayList<>();
        messages.add(assertThrows(RuntimeException.class,
                () -> service.create(registration(ID), manager)).getMessage());
        when(repository.findByNationalId(UIN)).thenReturn(Optional.of(existingMember(4L, "M-0004")));
        messages.add(assertThrows(RuntimeException.class,
                () -> service.create(registration(null), manager)).getMessage());
        Member member = verifiedMember(UIN);
        when(repository.findById(5L)).thenReturn(Optional.of(member));
        messages.add(assertThrows(RuntimeException.class,
                () -> service.update(5L, request("0722123456", ID))).getMessage());

        for (String message : messages) {
            assertFalse(message.contains(UIN), message);
            assertFalse(message.contains("6276"), message);
            assertFalse(message.contains(ID), message);
        }
        assertFalse(output.getAll().contains(UIN));
        assertFalse(output.getAll().contains(ID));
    }

    @Test
    void aVerifiedMembersNationalIdCannotBeChanged() {
        Member member = verifiedMember(UIN);
        when(repository.findById(5L)).thenReturn(Optional.of(member));

        for (String other : List.of(ID, "5860356277")) {
            com.coopaggregate.identity.IdentityRequestException e = assertThrows(
                    com.coopaggregate.identity.IdentityRequestException.class,
                    () -> service.update(5L, request("0722123456", other)));

            assertEquals("VERIFIED_FIELD_LOCKED", e.errorCode());
            assertEquals("The national ID of a verified member cannot be changed.", e.getMessage());
        }
        assertEquals(UIN, member.getNationalId());
        assertEquals("+250780000000", member.getPhone(), "nothing changed");
    }

    @Test
    void aVerifiedMembersOwnNationalIdSentBackUnchangedIsAccepted() {
        Member member = verifiedMember(UIN);
        when(repository.findById(5L)).thenReturn(Optional.of(member));
        when(repository.findByNationalId(UIN)).thenReturn(Optional.of(member));

        assertEquals(UIN, service.update(5L, request("0722123456", " " + UIN + " ")).nationalId());
        assertEquals("NATIONAL_ID_REQUIRED", assertThrows(NationalIdException.class,
                () -> service.update(5L, request("0722123456", null))).errorCode(), "still never removed");
    }

    @Test
    void aVerifiedMemberWithoutANationalIdMayStillGetATypedOne() {
        Member member = verifiedMember(null);
        when(repository.findById(5L)).thenReturn(Optional.of(member));

        assertEquals("NATIONAL_ID_INVALID", assertThrows(NationalIdException.class,
                () -> service.update(5L, request("0722123456", UIN))).errorCode());
        assertEquals(ID, service.update(5L, request("0722123456", ID)).nationalId());
    }

    private static Member verifiedMember(String nationalId) {
        Member member = existingMember(5L, "M-0005");
        member.setNationalId(nationalId);
        member.markIdentityVerified(VerificationMethod.PASSWORD, Instant.parse("2026-10-22T08:00:00Z"),
                "pairwise-sub");
        return member;
    }

    private void doAnswerMarkVerified() {
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<Member>getArgument(2).markIdentityVerified(VerificationMethod.PASSWORD,
                    Instant.parse("2026-10-22T08:00:00Z"), "pairwise-sub");
            return null;
        }).when(identityVerificationService).attach(eq(9L), eq(manager), any(Member.class));
    }

    private static NewMemberRequest registration(String nationalId) {
        return new NewMemberRequest(9L, null, null, nationalId, null, null, null);
    }

    @Test
    void summaryReturnsCounts() {
        when(repository.count()).thenReturn(12L);
        when(repository.countByStatus(MemberStatus.ACTIVE)).thenReturn(10L);
        when(repository.countByStatus(MemberStatus.INACTIVE)).thenReturn(2L);
        when(repository.countByJoinDateBetween(any(), any())).thenReturn(3L);

        MemberSummaryResponse summary = service.summary(LocalDate.of(2026, 10, 9));

        assertEquals(new MemberSummaryResponse(12, 10, 2, 3), summary);
    }

    @Test
    void joinedThisMonthCountsFromFirstToLastDayOfMonth() {
        service.summary(LocalDate.of(2026, 10, 9));
        verify(repository).countByJoinDateBetween(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        service.summary(LocalDate.of(2028, 2, 29));
        verify(repository).countByJoinDateBetween(LocalDate.of(2028, 2, 1), LocalDate.of(2028, 2, 29));
    }

    // --- Search by national ID: a whole ID of 6 to 20 letters or digits, exact match only

    @Test
    void searchFindsAWholeNationalIdOfAnyLengthByExactMatch() {
        for (String term : List.of(" 1199880012345678 ", "5860356276", "AB1234", "12345678901234567890")) {
            SearchQuery search = search(term);

            verify(search.cb()).equal(search.nationalId(), term.trim());
            verify(search.cb(), never()).like(eq(search.nationalId()), any(String.class));
        }
    }

    @Test
    void aTermThatCannotBeAWholeNationalIdNeverSearchesIt() {
        for (String term : List.of("12345", "123456789012345678901", "RW-1199-88", "1199 8800 1234", "M-0005",
                "+250788123456")) {
            SearchQuery search = search(term);

            verify(search.root(), never()).get("nationalId");
            verify(search.cb(), never()).equal(eq(search.nationalId()), any(Object.class));
        }
    }

    @Test
    void partOfANationalIdNeverFindsAMember() {
        // 11998800 is the start of the member's 1199880012345678: it is only compared as a whole ID, never as a part.
        SearchQuery search = search("11998800");

        verify(search.cb()).equal(search.nationalId(), "11998800");
        verify(search.cb(), never()).equal(search.nationalId(), "1199880012345678");
        verify(search.cb(), never()).like(eq(search.nationalId()), any(String.class));
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void aSearchByNationalIdIsNeverLoggedAndTheListNeverHoldsTheFullId(
            org.springframework.boot.test.system.CapturedOutput output) {
        Member member = existingMember(5L, "M-0005");
        member.setNationalId("5860356276");
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(member)));

        var page = service.list("5860356276", null, 0, 20);

        assertEquals(1, page.content().size());
        assertEquals("\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u2022\u20226276",
                page.content().get(0).nationalIdMasked());
        assertFalse(page.content().get(0).toString().contains("5860356276"));
        assertFalse(java.util.Arrays.stream(MemberListItem.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("nationalId")));
        assertFalse(output.getAll().contains("5860356276"));
    }

    private record SearchQuery(Root<Member> root, CriteriaBuilder cb, Path<String> nationalId) {
    }

    // Runs a list search and builds its query against mocks, to see which comparisons it makes.
    @SuppressWarnings("unchecked")
    private SearchQuery search(String term) {
        org.mockito.Mockito.clearInvocations(repository);
        ArgumentCaptor<Specification<Member>> spec = ArgumentCaptor.forClass(Specification.class);
        when(repository.findAll(spec.capture(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.list(term, null, 0, 20);

        Root<Member> root = mock(Root.class);
        Path<String> nationalId = mock(Path.class);
        doReturn(nationalId).when(root).get("nationalId");
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        spec.getValue().toPredicate(root, mock(CriteriaQuery.class), cb);
        return new SearchQuery(root, cb, nationalId);
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
