package com.coopaggregate.publicapi;

import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.error.ApiError;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/** The only endpoints that work without logging in. Nothing here may show members, stock, prices or the ledger. */
@RestController
@RequestMapping("/api/public")
@Tag(name = "Public", description = "For buyers, no login. Grades, cooperative contact details and purchase requests")
public class PublicController {

    private final PublicService publicService;
    private final PublicRequestService requestService;

    public PublicController(PublicService publicService, PublicRequestService requestService) {
        this.publicService = publicService;
        this.requestService = requestService;
    }

    @GetMapping("/grades")
    @Operation(summary = "Active grades with their description, size, packaging and a stock hint (AVAILABLE, LIMITED "
            + "or NONE). No prices or kilograms. Limited per IP address (429); may be cached for 60 seconds")
    public ResponseEntity<List<PublicGradeResponse>> grades(HttpServletRequest request) {
        // A minute-old hint is good enough, and caching keeps the stock calculation off every page view.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic())
                .body(publicService.grades(request.getRemoteAddr()));
    }

    @GetMapping("/info")
    @Operation(summary = "Cooperative name, location, email and the smallest order in kg")
    public PublicInfoResponse info() {
        return publicService.info();
    }

    @PostMapping("/requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Send a purchase request. Returns a code like BR-2026-7K2Q to look it up later. Limited per "
            + "IP address and per phone number (429). Leave `website` empty: it is a honeypot for bots")
    public PublicRequestCreatedResponse submit(@Valid @RequestBody PublicRequestForm form,
                                               HttpServletRequest request) {
        return requestService.submit(form, request.getRemoteAddr());
    }

    @GetMapping("/requests/{code}")
    @Operation(summary = "The status of a request and the manager's public message. The phone number must be the one "
            + "the request was sent with; otherwise 404, the same as for an unknown code")
    public PublicRequestStatusResponse status(@PathVariable String code,
                                              @RequestParam(required = false) String phone,
                                              HttpServletRequest request) {
        return requestService.status(code, phone, request.getRemoteAddr());
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiError> handleTooManyRequests(TooManyRequestsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(e.getRetryAfterSeconds()))
                .body(new ApiError(e.getMessage()));
    }
}
