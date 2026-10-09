package com.coopaggregate.buyer;

import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.common.RwandanPhoneNumber;

@Service
public class BuyerService {

    private final BuyerRepository buyerRepository;

    public BuyerService(BuyerRepository buyerRepository) {
        this.buyerRepository = buyerRepository;
    }

    @Transactional(readOnly = true)
    public List<BuyerResponse> list(String search) {
        return buyerRepository.findAll(matching(search), Sort.by("name").and(Sort.by("id"))).stream()
                .map(BuyerResponse::from)
                .toList();
    }

    @Transactional
    public BuyerResponse create(NewBuyerRequest request) {
        String phone = RwandanPhoneNumber.normalize(request.phone());
        buyerRepository.findByPhone(phone).ifPresent(other -> {
            throw new IllegalStateException("Phone number " + phone + " is already used by buyer " + other.getName() + ".");
        });

        Buyer buyer = new Buyer();
        buyer.setName(request.name().trim());
        buyer.setPhone(phone);
        buyer.setType(request.type() == null || request.type().isBlank() ? null : request.type().trim());
        return BuyerResponse.from(buyerRepository.save(buyer));
    }

    private static Specification<Buyer> matching(String search) {
        return (root, query, cb) -> {
            if (search == null || search.isBlank()) {
                return cb.conjunction();
            }
            String term = search.trim();
            // Phones are stored as +2507XXXXXXXX, so a search for 07XXXXXXXX must be converted first.
            String phoneTerm = RwandanPhoneNumber.isValid(term) ? RwandanPhoneNumber.normalize(term) : term;
            return cb.or(
                    cb.like(cb.lower(root.get("name")), "%" + term.toLowerCase(Locale.ROOT) + "%"),
                    cb.like(root.get("phone"), "%" + phoneTerm + "%"));
        };
    }
}
