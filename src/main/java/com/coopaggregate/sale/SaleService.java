package com.coopaggregate.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.buyer.Buyer;
import com.coopaggregate.buyer.BuyerRepository;
import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.common.PageResponse;
import com.coopaggregate.common.ShortCodes;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.ledger.LedgerEntryType;
import com.coopaggregate.ledger.LedgerService;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;

import jakarta.persistence.criteria.Predicate;

@Service
public class SaleService {

    private static final int MAX_PAGE_SIZE = 100;

    private final SaleRepository saleRepository;
    private final LotRepository lotRepository;
    private final BuyerRepository buyerRepository;
    private final LotService lotService;
    private final LedgerService ledgerService;

    public SaleService(SaleRepository saleRepository, LotRepository lotRepository,
                       BuyerRepository buyerRepository, LotService lotService, LedgerService ledgerService) {
        this.saleRepository = saleRepository;
        this.lotRepository = lotRepository;
        this.buyerRepository = buyerRepository;
        this.lotService = lotService;
        this.ledgerService = ledgerService;
    }

    @Transactional
    public RecordedSale record(SaleRequest request, Manager manager) {
        // The row lock makes sales and losses of the same lot wait for each other, so two sales cannot
        // both pass the remaining kg check. It also makes a repeated client UUID wait for the first request.
        Lot lot = lotRepository.findForUpdateById(request.lotId())
                .orElseThrow(() -> new BadRequestException("Lot " + request.lotId() + " does not exist."));

        // Checked before the lot status, so a re-sent sale is still answered after the lot became SOLD.
        Optional<Sale> existing = saleRepository.findByClientUuid(request.clientUuid());
        if (existing.isPresent()) {
            return new RecordedSale(SaleResponse.from(existing.get()), false);
        }

        if (lot.getStatus() != LotStatus.CLOSED) {
            throw new BadRequestException("Lot " + lot.getLotCode() + " is " + lot.getStatus()
                    + ". Only a closed lot can be sold.");
        }
        Buyer buyer = buyerRepository.findById(request.buyerId())
                .orElseThrow(() -> new BadRequestException("Buyer " + request.buyerId() + " does not exist."));

        BigDecimal remainingKg = lotService.quantitiesOf(lot).remainingKg();
        if (request.quantityKg().compareTo(remainingKg) > 0) {
            throw new BadRequestException("Only " + remainingKg.toPlainString() + " kg remain in lot "
                    + lot.getLotCode() + ".");
        }

        Sale sale = new Sale();
        sale.setClientUuid(request.clientUuid());
        sale.setSaleCode(ShortCodes.unique("SAL-", saleRepository::existsBySaleCode));
        sale.setLot(lot);
        sale.setBuyer(buyer);
        sale.setSaleDate(request.soldAt() != null ? request.soldAt() : KigaliTime.today());
        sale.setQuantityKg(request.quantityKg());
        sale.setPricePerKg(request.pricePerKg());
        sale.setTotalAmount(totalAmount(request.quantityKg(), request.pricePerKg()));
        sale.setBelowFloorPrice(isBelowFloor(lot, request.pricePerKg()));
        Sale saved = saleRepository.save(sale);
        ledgerService.record(LedgerEntryType.SALE, "SALE", saved.getId(), saved.getQuantityKg(),
                saved.getTotalAmount(),
                "Sale " + saved.getSaleCode() + " from " + lot.getLotCode() + " to " + buyer.getName(), manager);

        if (request.quantityKg().compareTo(remainingKg) == 0) {
            lot.setStatus(LotStatus.SOLD);
        }
        return new RecordedSale(SaleResponse.from(saved), true);
    }

    @Transactional(readOnly = true)
    public PageResponse<SaleResponse> list(Long lotId, Long buyerId, int page, int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "saleDate", "id"));
        return PageResponse.from(saleRepository.findAll(matching(lotId, buyerId), pageRequest)
                .map(SaleResponse::from));
    }

    // Amounts are stored in whole RWF, so fractions are rounded (half up).
    private static long totalAmount(BigDecimal quantityKg, long pricePerKg) {
        return quantityKg.multiply(BigDecimal.valueOf(pricePerKg))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    // Only a warning for the manager; a grade without a floor price is never below it.
    private static boolean isBelowFloor(Lot lot, long pricePerKg) {
        Long floorPricePerKg = lot.getGrade().getFloorPricePerKg();
        return floorPricePerKg != null && pricePerKg < floorPricePerKg;
    }

    private static Specification<Sale> matching(Long lotId, Long buyerId) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (lotId != null) {
                predicates.add(cb.equal(root.get("lot").get("id"), lotId));
            }
            if (buyerId != null) {
                predicates.add(cb.equal(root.get("buyer").get("id"), buyerId));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
