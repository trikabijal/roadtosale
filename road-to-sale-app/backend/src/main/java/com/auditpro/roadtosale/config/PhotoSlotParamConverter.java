package com.auditpro.roadtosale.config;

import com.auditpro.roadtosale.domain.PhotoSlot;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** Binds the multipart {@code slot} param (lowercase wire value) to {@link PhotoSlot}. */
@Component
public class PhotoSlotParamConverter implements Converter<String, PhotoSlot> {

    @Override
    public PhotoSlot convert(String source) {
        return PhotoSlot.fromWire(source);
    }
}
