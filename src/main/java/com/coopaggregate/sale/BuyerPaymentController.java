package com.coopaggregate.sale;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.coopaggregate.common.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@RestController
@RequestMapping("/api/buyer-payments")
@Tag(name = "Buyer payments", description = "Money in: a buyer pays each order once, in full")
@SecurityRequirement(name = "bearerAuth")
public class BuyerPaymentController {

    private final BuyerPaymentService buyerPaymentService;

    public BuyerPaymentController(BuyerPaymentService buyerPaymentService) {
        this.buyerPaymentService = buyerPaymentService;
    }

    @GetMapping
    @Operation(summary = "Buyer payments, one per order, newest first (max 100 per page): buyer, method, reference, "
            + "total, the sales of each grade, and what each farmer was credited, summed per farmer")
    public PageResponse<BuyerPaymentResponse> list(@RequestParam(required = false) Long buyerId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return buyerPaymentService.list(buyerId, page, size);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "One buyer payment (an order), the same as its row in the list. 404 when it does not exist")
    public BuyerPaymentResponse get(@PathVariable Long orderId) {
        return buyerPaymentService.get(orderId);
    }
}
