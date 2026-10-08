package com.coopaggregate.member;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

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
            @Parameter(description = "Part of the name, phone number or member code (case-insensitive)")
            @RequestParam(required = false) String search,
            @Parameter(description = "true for active members only, false for inactive members only")
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return memberService.list(search, active, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one member")
    public MemberResponse get(@PathVariable Long id) {
        return memberService.get(id);
    }
}
