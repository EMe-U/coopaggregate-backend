package com.coopaggregate.member;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/members")
@Tag(name = "Members", description = "Cooperative members. Members are never deleted, only deactivated")
@SecurityRequirement(name = "bearerAuth")
public class MemberController {

    private final MemberService memberService;

    public MemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping
    @Operation(summary = "List members sorted by name (max 100 per page)")
    public PageResponse<MemberResponse> list(
            @Parameter(description = "Part of the name, phone number, member code or national ID (case-insensitive)")
            @RequestParam(required = false) String search,
            @Parameter(description = "true for active members only, false for inactive members only")
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return memberService.list(search, active, page, size);
    }

    @GetMapping("/summary")
    @Operation(summary = "Count all, active and inactive members, and members who joined this month (Kigali time)")
    public MemberSummaryResponse summary() {
        return memberService.summary();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one member")
    public MemberResponse get(@PathVariable Long id) {
        return memberService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a member. The member code (MEM-0001, ...) is generated")
    public MemberResponse create(@Valid @RequestBody MemberRequest request) {
        return memberService.create(request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a member's details. The member code and status do not change")
    public MemberResponse update(@PathVariable Long id, @Valid @RequestBody MemberRequest request) {
        return memberService.update(id, request);
    }

    @PatchMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a member. Their deliveries, payments and ledger history are kept")
    public MemberResponse deactivate(@PathVariable Long id) {
        return memberService.setActive(id, false);
    }

    @PatchMapping("/{id}/activate")
    @Operation(summary = "Activate a member again")
    public MemberResponse activate(@PathVariable Long id) {
        return memberService.setActive(id, true);
    }
}
