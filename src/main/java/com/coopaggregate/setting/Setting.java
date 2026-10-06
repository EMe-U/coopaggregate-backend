package com.coopaggregate.setting;

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

    private String committeeEmails;

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

    public String getCommitteeEmails() {
        return committeeEmails;
    }

    public void setCommitteeEmails(String committeeEmails) {
        this.committeeEmails = committeeEmails;
    }
}
