package com.coopaggregate.batch;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SaleAllocationRepository extends JpaRepository<SaleAllocation, Long> {

    List<SaleAllocation> findBySaleIdOrderById(Long saleId);
}
