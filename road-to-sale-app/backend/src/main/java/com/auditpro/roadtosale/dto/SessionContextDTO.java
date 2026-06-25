package com.auditpro.roadtosale.dto;

/** Optional free-form session context shown back to the app. */
public record SessionContextDTO(String customerName, String vehicleOfInterest) {
}
