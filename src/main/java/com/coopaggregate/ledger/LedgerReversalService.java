package com.coopaggregate.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.delivery.DeliveryService;
import com.coopaggregate.loss.LossRepository;
import com.coopaggregate.lot.LotStatus;
import com.coopaggregate.manager.Manager;
import com.coopaggregate.payment.PaymentService;
import com.coopaggregate.sale.SaleRepository;

/**
 * Reverses a ledger entry through the business action of its type, so the delivery or payment is reversed
 * together with its ledger entry. A ledger reversal on its own would leave the totals counting the record.
 */
@Service
public class LedgerReversalService {

    private final LedgerEntryRepository ledgerEntryRepository;
    private final DeliveryService deliveryService;
    private final PaymentService paymentService;
    private final SaleRepository saleRepository;
    private final LossRepository lossRepository;

    public LedgerReversalService(LedgerEntryRepository ledgerEntryRepository, DeliveryService deliveryService,
                                 PaymentService paymentService, SaleRepository saleRepository,
                                 LossRepository lossRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.deliveryService = deliveryService;
        this.paymentService = paymentService;
        this.saleRepository = saleRepository;
        this.lossRepository = lossRepository;
    }

    @Transactional
    public LedgerEntry reverse(Long entryId, String reason, Manager manager) {
        LedgerEntry entry = ledgerEntryRepository.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("Ledger entry " + entryId + " does not exist."));
        Long recordId = entry.getRelatedEntityId();

        return switch (entry.getEntryType()) {
            case DELIVERY -> deliveryService.reverse(recordId, reason, manager).getReversalLedgerEntry();
            case PAYMENT -> paymentService.reverse(recordId, reason, manager).getReversalLedgerEntry();
            case REVERSAL -> throw new IllegalStateException(
                    "Ledger entry " + entryId + " is a reversal and cannot be reversed.");
            case SHARE -> throw new IllegalStateException(
                    "Shares cannot be reversed: the lot's money has already been divided between its members.");
            case SALE -> throw new IllegalStateException(saleMessage(recordId));
            case LOSS -> throw new IllegalStateException(lossMessage(recordId));
            case SALE_CREDIT -> throw new IllegalStateException(
                    "A member's credit from a sale cannot be reversed on its own.");
        };
    }

    private String saleMessage(Long saleId) {
        boolean shared = saleRepository.findById(saleId)
                .map(sale -> sale.getLot().getStatus() == LotStatus.SHARED)
                .orElse(false);
        return shared
                ? "The sale's lot has already been shared, so the sale can no longer be reversed."
                : "Sales cannot be reversed yet.";
    }

    private String lossMessage(Long lossId) {
        boolean lotHasSales = lossRepository.findById(lossId)
                .map(loss -> saleRepository.existsByLotId(loss.getLot().getId()))
                .orElse(false);
        return lotHasSales
                ? "The loss's lot already has sales, so the loss can no longer be reversed."
                : "Losses cannot be reversed yet.";
    }
}
