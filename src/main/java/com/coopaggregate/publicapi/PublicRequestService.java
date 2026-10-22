package com.coopaggregate.publicapi;

import java.time.Duration;

import org.springframework.stereotype.Service;

import com.coopaggregate.buyer.BuyerRequest;
import com.coopaggregate.buyer.BuyerRequestService;
import com.coopaggregate.buyer.BuyerRequestStatus;
import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.common.RwandanPhoneNumber;
import com.coopaggregate.common.ShortCodes;

/**
 * The public side of purchase requests: a honeypot and rate limits in front of BuyerRequestService. Nothing here
 * logs a name, phone number, email or IP address.
 */
@Service
public class PublicRequestService {

    private final BuyerRequestService requestService;
    private final RateLimiter submitPerIp = new RateLimiter(10, Duration.ofHours(1));
    private final RateLimiter submitPerPhone = new RateLimiter(3, Duration.ofDays(1));
    private final RateLimiter lookupPerIp = new RateLimiter(30, Duration.ofMinutes(10));

    public PublicRequestService(BuyerRequestService requestService) {
        this.requestService = requestService;
    }

    public PublicRequestCreatedResponse submit(PublicRequestForm form, String clientIp) {
        // A bot that fills the hidden field gets a normal-looking answer and nothing is saved. It does not count
        // against the limits, and the code it gets belongs to no request.
        if (form.website() != null && !form.website().isBlank()) {
            return new PublicRequestCreatedResponse(
                    ShortCodes.unique("BR-" + KigaliTime.today().getYear() + "-", code -> false),
                    BuyerRequestStatus.PENDING);
        }
        limit(submitPerIp, "ip:" + clientIp);
        limit(submitPerPhone, "phone:" + RwandanPhoneNumber.normalize(form.phone()));

        BuyerRequest request = requestService.createFromPublic(form.name(), form.phone(), form.email(),
                form.gradeCode(), form.quantityKg(), form.note());
        return new PublicRequestCreatedResponse(request.getReferenceCode(), request.getStatus());
    }

    public PublicRequestStatusResponse status(String code, String phone, String clientIp) {
        limit(lookupPerIp, "ip:" + clientIp);
        BuyerRequest request = requestService.findForPublic(code, phone);
        return new PublicRequestStatusResponse(request.getReferenceCode(), request.getStatus(),
                request.getPublicMessage());
    }

    private static void limit(RateLimiter limiter, String key) {
        long wait = limiter.tryAcquire(key);
        if (wait > 0) {
            throw new TooManyRequestsException(wait);
        }
    }
}
