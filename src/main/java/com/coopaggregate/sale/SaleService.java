package com.coopaggregate.sale;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.buyer.Buyer;
import com.coopaggregate.buyer.BuyerRepository;
import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.common.ShortCodes;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.lot.Lot;
import com.coopaggregate.lot.LotRepository;
import com.coopaggregate.lot.LotService;
import com.coopaggregate.lot.LotStatus;

@Service
public class SaleService {

    private final SaleRepository saleRepository;
    private final LotRepository lotRepository;
    private final BuyerRepository buyerRepository;
    private final LotService lotService;

    public SaleService(SaleRepository saleRepository, LotRepository lotRepository,
                       BuyerRepository buyerRepository, LotService lotService) {
        this.saleRepository = saleRepository;
        this.lotRepository = lotRepository;
        this.buyerRepository = buyerRepository;
        this.lotService = lotService;
    }

    @Transactional
    public RecordedSale record(SaleRequest request) {
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
        return new RecordedSale(SaleResponse.from(saleRepository.save(sale)), true);
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
}
