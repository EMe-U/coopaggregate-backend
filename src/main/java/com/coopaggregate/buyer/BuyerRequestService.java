package com.coopaggregate.buyer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.coopaggregate.common.KigaliTime;
import com.coopaggregate.common.RwandanPhoneNumber;
import com.coopaggregate.common.ShortCodes;
import com.coopaggregate.error.BadRequestException;
import com.coopaggregate.grade.Grade;
import com.coopaggregate.grade.GradeRepository;
import com.coopaggregate.setting.SettingRepository;

import jakarta.persistence.EntityNotFoundException;

/** Purchase requests from buyers. The public side creates and looks them up; the manager side works on them. */
@Service
public class BuyerRequestService {

    /** The same for an unknown code and a wrong phone, so a code cannot be tested without its phone. */
    public static final String NOT_FOUND = "Request not found.";

    private final BuyerRequestRepository requestRepository;
    private final GradeRepository gradeRepository;
    private final SettingRepository settingRepository;

    public BuyerRequestService(BuyerRequestRepository requestRepository, GradeRepository gradeRepository,
                               SettingRepository settingRepository) {
        this.requestRepository = requestRepository;
        this.gradeRepository = gradeRepository;
        this.settingRepository = settingRepository;
    }

    /** Saves a request from the public page as PENDING, with a code like BR-2026-7K2Q. */
    @Transactional
    public BuyerRequest createFromPublic(String name, String phone, String email, String gradeCode,
                                         BigDecimal quantityKg, String note) {
        Grade grade = gradeRepository.findByCodeAndActiveTrue(gradeCode.trim().toUpperCase())
                .orElseThrow(() -> new BadRequestException("Unknown grade. Choose one of the grades shown."));
        BigDecimal minOrderKg = settingRepository.findById(1L)
                .orElseThrow(() -> new IllegalStateException("The settings row is missing."))
                .getMinOrderKg();
        if (quantityKg.compareTo(minOrderKg) < 0) {
            throw new BadRequestException("The smallest order is " + minOrderKg.stripTrailingZeros().toPlainString()
                    + " kg.");
        }

        BuyerRequest request = new BuyerRequest();
        request.setReferenceCode(newCode());
        request.setGrade(grade);
        request.setQuantityKg(quantityKg);
        request.setRequesterName(name.trim());
        request.setRequesterPhone(RwandanPhoneNumber.normalize(phone));
        request.setRequesterEmail(blankToNull(email));
        request.setMessage(blankToNull(note));
        return requestRepository.save(request);
    }

    /** The request, only if the phone is the one it was sent with. Otherwise the same 404 as an unknown code. */
    @Transactional(readOnly = true)
    public BuyerRequest findForPublic(String code, String phone) {
        if (code == null || !RwandanPhoneNumber.isValid(phone)) {
            throw new EntityNotFoundException(NOT_FOUND);
        }
        BuyerRequest request = requestRepository.findByReferenceCode(code.trim().toUpperCase())
                .orElseThrow(() -> new EntityNotFoundException(NOT_FOUND));
        byte[] given = RwandanPhoneNumber.normalize(phone).getBytes(StandardCharsets.UTF_8);
        byte[] stored = request.getRequesterPhone() == null ? new byte[0]
                : request.getRequesterPhone().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(given, stored)) {
            throw new EntityNotFoundException(NOT_FOUND);
        }
        return request;
    }

    /** BR-2026-XXXX: random, from letters and digits that are not confused with each other. */
    String newCode() {
        return ShortCodes.unique("BR-" + KigaliTime.today().getYear() + "-", requestRepository::existsByReferenceCode);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
