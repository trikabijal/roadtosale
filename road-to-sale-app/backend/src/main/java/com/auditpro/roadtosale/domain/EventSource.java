package com.auditpro.roadtosale.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/** How a cue was detected. Lowercase wire values per the contract. */
public enum EventSource {
    @JsonProperty("feature")
    FEATURE("feature"),
    @JsonProperty("workflow")
    WORKFLOW("workflow");

    private final String wire;

    EventSource(String wire) {
        this.wire = wire;
    }

    public String getWire() {
        return wire;
    }

    public static EventSource fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (EventSource s : values()) {
            if (s.wire.equalsIgnoreCase(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("Unknown event source: " + value);
    }
}
