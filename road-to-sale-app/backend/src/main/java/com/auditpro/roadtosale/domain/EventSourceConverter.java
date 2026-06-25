package com.auditpro.roadtosale.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** Persists {@link EventSource} as its lowercase wire value ('feature'/'workflow'). */
@Converter(autoApply = false)
public class EventSourceConverter implements AttributeConverter<EventSource, String> {

    @Override
    public String convertToDatabaseColumn(EventSource attribute) {
        return attribute == null ? null : attribute.getWire();
    }

    @Override
    public EventSource convertToEntityAttribute(String dbData) {
        return EventSource.fromWire(dbData);
    }
}
