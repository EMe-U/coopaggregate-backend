package com.coopaggregate.sale;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.batch.SaleAllocation;
import com.coopaggregate.batch.SaleAllocationRepository;
import com.coopaggregate.common.PageResponse;

import jakarta.persistence.EntityNotFoundException;

/** Money in from buyers: one payment per order, newest first, with what each farmer was credited from it. */
@Service
public class BuyerPaymentService {

    private static final int MAX_PAGE_SIZE = 100;

    private final SaleOrderRepository saleOrderRepository;
    private final SaleRepository saleRepository;
    private final SaleAllocationRepository saleAllocationRepository;

    public BuyerPaymentService(SaleOrderRepository saleOrderRepository, SaleRepository saleRepository,
                               SaleAllocationRepository saleAllocationRepository) {
        this.saleOrderRepository = saleOrderRepository;
        this.saleRepository = saleRepository;
        this.saleAllocationRepository = saleAllocationRepository;
    }

    @Transactional(readOnly = true)
    public PageResponse<BuyerPaymentResponse> list(Long buyerId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "saleDate", "id"));
        Page<SaleOrder> orders = buyerId == null
                ? saleOrderRepository.findAllBy(pageRequest)
                : saleOrderRepository.findByBuyerId(buyerId, pageRequest);
        return new PageResponse<>(responsesOf(orders.getContent()), orders.getNumber(), orders.getSize(),
                orders.getTotalElements(), orders.getTotalPages());
    }

    @Transactional(readOnly = true)
    public BuyerPaymentResponse get(Long orderId) {
        SaleOrder order = saleOrderRepository.findWithBuyerById(orderId)
                .orElseThrow(() -> new EntityNotFoundException("Buyer payment " + orderId + " does not exist."));
        return responsesOf(List.of(order)).get(0);
    }

    // The sales and batches of a whole page in two queries, not two per order.
    private List<BuyerPaymentResponse> responsesOf(List<SaleOrder> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        List<Sale> sales = saleRepository.findBySaleOrderIdInOrderByIdAsc(orders.stream().map(SaleOrder::getId).toList());
        Map<Long, List<Sale>> salesByOrder = sales.stream()
                .collect(Collectors.groupingBy(sale -> sale.getSaleOrder().getId()));
        Map<Long, List<SaleAllocation>> batchesBySale = sales.isEmpty() ? Map.of() : saleAllocationRepository
                .findBySaleIdInOrderByIdAsc(sales.stream().map(Sale::getId).toList()).stream()
                .collect(Collectors.groupingBy(allocation -> allocation.getSale().getId()));
        return orders.stream()
                .map(order -> {
                    List<Sale> orderSales = salesByOrder.getOrDefault(order.getId(), List.of());
                    List<SaleAllocation> batches = orderSales.stream()
                            .flatMap(sale -> batchesBySale.getOrDefault(sale.getId(), List.of()).stream())
                            .toList();
                    return BuyerPaymentResponse.from(order, orderSales, batches);
                })
                .toList();
    }
}
