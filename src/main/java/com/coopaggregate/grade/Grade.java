package com.coopaggregate.grade;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

@Entity
public class Grade {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;

    private String name;

    private String nameRw;

    private String sizeDescription;

    private String description;

    private String packaging;

    private Long floorPricePerKg;

    private boolean active = true;

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNameRw() {
        return nameRw;
    }

    public void setNameRw(String nameRw) {
        this.nameRw = nameRw;
    }

    public String getSizeDescription() {
        return sizeDescription;
    }

    public void setSizeDescription(String sizeDescription) {
        this.sizeDescription = sizeDescription;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getPackaging() {
        return packaging;
    }

    public void setPackaging(String packaging) {
        this.packaging = packaging;
    }

    public Long getFloorPricePerKg() {
        return floorPricePerKg;
    }

    public void setFloorPricePerKg(Long floorPricePerKg) {
        this.floorPricePerKg = floorPricePerKg;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
