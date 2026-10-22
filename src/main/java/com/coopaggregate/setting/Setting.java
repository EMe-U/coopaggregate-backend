package com.coopaggregate.setting;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Setting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long deductionPerKg = 5L;

    private boolean smsEnabled = true;

    // Comma-separated; use getCommitteeEmailList and setCommitteeEmailList.
    private String committeeEmails;

    private String cooperativeEmail;

    private String location;

    private BigDecimal minOrderKg = new BigDecimal("100");

    public Long getId() {
        return id;
    }

    public Long getDeductionPerKg() {
        return deductionPerKg;
    }

    public void setDeductionPerKg(Long deductionPerKg) {
        this.deductionPerKg = deductionPerKg;
    }

    public boolean isSmsEnabled() {
        return smsEnabled;
    }

    public void setSmsEnabled(boolean smsEnabled) {
        this.smsEnabled = smsEnabled;
    }

    public List<String> getCommitteeEmailList() {
        if (committeeEmails == null || committeeEmails.isBlank()) {
            return List.of();
        }
        return Arrays.stream(committeeEmails.split(",")).map(String::trim).filter(email -> !email.isEmpty()).toList();
    }

    public void setCommitteeEmailList(List<String> emails) {
        this.committeeEmails = emails.isEmpty() ? null : String.join(",", emails);
    }

    public String getCooperativeEmail() {
        return cooperativeEmail;
    }

    public void setCooperativeEmail(String cooperativeEmail) {
        this.cooperativeEmail = cooperativeEmail;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public BigDecimal getMinOrderKg() {
        return minOrderKg;
    }

    public void setMinOrderKg(BigDecimal minOrderKg) {
        this.minOrderKg = minOrderKg;
    }
}
