package com.coopaggregate.sale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import com.coopaggregate.batch.SaleAllocation;
import com.coopaggregate.batch.SaleAllocationRepository;
import com.coopaggregate.buyer.Buyer;
import com.coopaggregate.common.PageResponse;
import com.coopaggregate.delivery.Delivery;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.member.Member;
import com.coopaggregate.payment.PaymentMethod;

import jakarta.persistence.EntityNotFoundException;

class BuyerPaymentServiceTest {

    private final SaleOrderRepository saleOrderRepository = mock(SaleOrderRepository.class);
    private final SaleRepository saleRepository = mock(SaleRepository.class);
    private final SaleAllocationRepository saleAllocationRepository = mock(SaleAllocationRepository.class);
    private final BuyerPaymentService service =
            new BuyerPaymentService(saleOrderRepository, saleRepository, saleAllocationRepository);

    private final Buyer kalisa = buyer(2L, "Kalisa Traders");
    private final Grade big = grade(1L, "Big");
    private final Grade small = grade(2L, "Small");
    private final Member claudine = member(5L, "M-0005", "Uwimana Claudine");
    private final Member bosco = member(6L, "M-0006", "Habimana Bosco");

    private final List<Sale> sales = new ArrayList<>();
    private final List<SaleAllocation> allocations = new ArrayList<>();
    private SaleOrder twoGrades;
    private SaleOrder oldSale;

    @BeforeEach
    void setUp() {
        // ORD-7K2Q, paid by MoMo: Big 120 kg at 300 (Claudine 70 kg, Bosco 50 kg) and Small 100 kg at 200 (Claudine).
        twoGrades = order(10L, "ORD-7K2Q", LocalDate.of(2026, 11, 10), PaymentMethod.MOMO, "MP2611100001");
        Sale bigSale = sale(11L, "SAL-AAAA", twoGrades, big, "120.00", 300L, 36_000L);
        Sale smallSale = sale(12L, "SAL-BBBB", twoGrades, small, "100.00", 200L, 20_000L);
        allocations.add(new SaleAllocation(bigSale, delivery(1L, claudine), new BigDecimal("70.00"), 21_000L, 350L));
        allocations.add(new SaleAllocation(bigSale, delivery(2L, bosco), new BigDecimal("50.00"), 15_000L, 250L));
        allocations.add(new SaleAllocation(smallSale, delivery(7L, claudine), new BigDecimal("100.00"), 20_000L,
                500L));

        // A sale from before sales by grade, given its own order by V42: no method, and its members were paid
        // through the lot share, so its batch has no money.
        oldSale = order(3L, "ORD-S-3", LocalDate.of(2026, 9, 1), null, null);
        Sale lotSale = sale(3L, "SAL-0003", oldSale, big, "40.00", 250L, 10_000L);
        allocations.add(new SaleAllocation(lotSale, delivery(9L, bosco), new BigDecimal("40.00")));

        when(saleRepository.findBySaleOrderIdInOrderByIdAsc(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return sales.stream().filter(sale -> ids.contains(sale.getSaleOrder().getId())).toList();
        });
        when(saleAllocationRepository.findBySaleIdInOrderByIdAsc(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            return allocations.stream().filter(allocation -> ids.contains(allocation.getSale().getId())).toList();
        });
        when(saleOrderRepository.findAllBy(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(twoGrades, oldSale)));
    }

    @Test
    void eachFarmerIsSummedOverAllTheirBatchesInEveryGradeOfTheOrder() {
        BuyerPaymentResponse payment = get(twoGrades);

        assertEquals(List.of(
                new BuyerPaymentResponse.FarmerShare(6L, "M-0006", "Habimana Bosco", new BigDecimal("50.00"),
                        15_000L, 250L, 14_750L),
                new BuyerPaymentResponse.FarmerShare(5L, "M-0005", "Uwimana Claudine", new BigDecimal("170.00"),
                        41_000L, 850L, 40_150L)), payment.farmers());
    }

    @Test
    void thePaymentShowsTheBuyerHowTheyPaidAndTheTotalOfItsGrades() {
        BuyerPaymentResponse payment = get(twoGrades);

        assertEquals("ORD-7K2Q", payment.orderCode());
        assertEquals("Kalisa Traders", payment.buyerName());
        assertEquals(LocalDate.of(2026, 11, 10), payment.paidAt());
        assertEquals(PaymentMethod.MOMO, payment.method());
        assertEquals("MP2611100001", payment.reference());
        assertEquals(new BigDecimal("220.00"), payment.totalKg());
        assertEquals(56_000L, payment.totalAmountRwf());
        assertEquals(1_100L, payment.totalDeductionRwf());
        assertEquals(54_900L, payment.totalNetRwf());
        // What the buyer paid is what the farmers were credited plus the deductions.
        assertEquals(payment.totalAmountRwf(), payment.totalNetRwf() + payment.totalDeductionRwf());
        assertEquals(List.of("Big", "Small"),
                payment.sales().stream().map(BuyerPaymentResponse.SaleLine::gradeName).toList());
    }

    @Test
    void anOldSaleShowsNoMethodAndItsFarmersHaveKgButNoMoney() {
        BuyerPaymentResponse payment = get(oldSale);

        assertNull(payment.method(), "shown as not recorded");
        assertNull(payment.reference());
        assertEquals(10_000L, payment.totalAmountRwf());
        assertNull(payment.totalDeductionRwf());
        assertNull(payment.totalNetRwf());
        BuyerPaymentResponse.FarmerShare farmer = payment.farmers().get(0);
        assertEquals(new BigDecimal("40.00"), farmer.kg());
        assertNull(farmer.grossRwf());
        assertNull(farmer.netRwf());
    }

    @Test
    void theListIsNewestFirstAtMost100PerPageAndLoadsAWholePageInTwoQueries() {
        PageResponse<BuyerPaymentResponse> page = service.list(null, 2, 500);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(saleOrderRepository).findAllBy(pageable.capture());
        assertEquals(2, pageable.getValue().getPageNumber());
        assertEquals(100, pageable.getValue().getPageSize());
        assertEquals(Sort.by(Sort.Direction.DESC, "saleDate", "id"), pageable.getValue().getSort());
        assertEquals(List.of("ORD-7K2Q", "ORD-S-3"), page.content().stream().map(BuyerPaymentResponse::orderCode).toList());
        verify(saleRepository).findBySaleOrderIdInOrderByIdAsc(List.of(10L, 3L));
        verify(saleAllocationRepository).findBySaleIdInOrderByIdAsc(List.of(11L, 12L, 3L));
    }

    @Test
    void theListCanBeLimitedToOneBuyer() {
        when(saleOrderRepository.findByBuyerId(eq(2L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(twoGrades)));

        PageResponse<BuyerPaymentResponse> page = service.list(2L, 0, 20);

        assertEquals(1, page.content().size());
        verify(saleOrderRepository, never()).findAllBy(any());
    }

    @Test
    void oneBuyerPaymentIsTheSameAsItsRowInTheList() {
        assertEquals(service.list(null, 0, 20).content().get(0), get(twoGrades));
    }

    @Test
    void anUnknownBuyerPaymentIsNotFound() {
        when(saleOrderRepository.findWithBuyerById(anyLong())).thenReturn(Optional.empty());

        EntityNotFoundException e = assertThrows(EntityNotFoundException.class, () -> service.get(404L));

        assertEquals("Buyer payment 404 does not exist.", e.getMessage());
    }

    // --- Helpers ---

    private BuyerPaymentResponse get(SaleOrder order) {
        when(saleOrderRepository.findWithBuyerById(order.getId())).thenReturn(Optional.of(order));
        return service.get(order.getId());
    }

    private SaleOrder order(Long id, String code, LocalDate date, PaymentMethod method, String reference) {
        SaleOrder order = new SaleOrder();
        ReflectionTestUtils.setField(order, "id", id);
        order.setOrderCode(code);
        order.setBuyer(kalisa);
        order.setSaleDate(date);
        order.setPaymentMethod(method);
        order.setPaymentReference(reference);
        return order;
    }

    private Sale sale(Long id, String code, SaleOrder order, Grade grade, String kg, long price, long total) {
        Sale sale = new Sale();
        ReflectionTestUtils.setField(sale, "id", id);
        sale.setSaleCode(code);
        sale.setSaleOrder(order);
        sale.setGrade(grade);
        sale.setBuyer(kalisa);
        sale.setQuantityKg(new BigDecimal(kg));
        sale.setPricePerKg(price);
        sale.setTotalAmount(total);
        sales.add(sale);
        return sale;
    }

    private static Delivery delivery(Long id, Member member) {
        Delivery delivery = new Delivery();
        ReflectionTestUtils.setField(delivery, "id", id);
        delivery.setMember(member);
        return delivery;
    }

    private static Buyer buyer(Long id, String name) {
        Buyer buyer = new Buyer();
        ReflectionTestUtils.setField(buyer, "id", id);
        buyer.setName(name);
        return buyer;
    }

    private static Grade grade(Long id, String name) {
        Grade grade = new Grade();
        ReflectionTestUtils.setField(grade, "id", id);
        grade.setName(name);
        return grade;
    }

    private static Member member(Long id, String code, String name) {
        Member member = new Member();
        ReflectionTestUtils.setField(member, "id", id);
        member.setMemberCode(code);
        member.setFullName(name);
        return member;
    }
}
