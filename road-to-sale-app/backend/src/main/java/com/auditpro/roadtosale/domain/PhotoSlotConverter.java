package com.auditpro.roadtosale.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Persists {@link PhotoSlot} as its lowercase wire value (e.g. 'front_left'). */
@Converter(autoApply = false)
public class PhotoSlotConverter implements AttributeConverter<PhotoSlot, String> {

    @Override
    public String convertToDatabaseColumn(PhotoSlot attribute) {
        return attribute == null ? null : attribute.getWire();
    }

    @Override
    public PhotoSlot convertToEntityAttribute(String dbData) {
        return PhotoSlot.fromWire(dbData);
    }
}
