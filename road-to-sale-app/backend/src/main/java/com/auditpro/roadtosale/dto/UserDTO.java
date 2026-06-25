package com.auditpro.roadtosale.dto;

import com.auditpro.roadtosale.domain.User;

import java.util.List;
import java.util.UUID;

/** App-facing user shape: {@code {id, username, name, dealershipId, roles}}. */
public record UserDTO(
        UUID id,
        String username,
        String name,
        UUID dealershipId,
        List<String> roles) {

    public static UserDTO from(User u) {
        return new UserDTO(
                u.getId(),
                u.getUsername(),
                u.getName(),
                u.getDealershipId(),
                u.getRoles() == null ? List.of() : List.of(u.getRoles()));
    }
}
