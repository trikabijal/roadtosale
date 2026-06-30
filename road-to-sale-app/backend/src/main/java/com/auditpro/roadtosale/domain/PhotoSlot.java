package com.auditpro.roadtosale.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Trade-in photo slot. Lowercase wire values per the contract/schema. */
public enum PhotoSlot {
    FRONT_LEFT("front_left"),
    FRONT_RIGHT("front_right"),
    REAR_LEFT("rear_left"),
    REAR_RIGHT("rear_right"),
    INTERIOR("interior"),
    ODOMETER("odometer"),
    VIN("vin");

    private final String wire;

    PhotoSlot(String wire) {
        this.wire = wire;
    }

    @JsonValue
    public String getWire() {
        return wire;
    }

    @JsonCreator
    public static PhotoSlot fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (PhotoSlot s : values()) {
            if (s.wire.equalsIgnoreCase(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown photo slot: " + value);
    }
}
