package com.coopaggregate.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/dashboard")
@Tag(name = "Dashboard", description = "Numbers for the home screen")
@SecurityRequirement(name = "bearerAuth")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping
    @Operation(summary = "Members, stock (kg left, split into sellable, waiting and disputed kg, in total and per "
            + "grade), today's deliveries, open lots, money owed, grades with sellable kg, sold lots waiting to be "
            + "shared, and deliveries per grade for the last 4 weeks (Kigali time)")
    public DashboardResponse dashboard() {
        return dashboardService.dashboard();
    }
}
