package com.checkSheet.config;

import com.checkSheet.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class CustomUserDetails implements UserDetails {
    private final User user;
    private final List<String> permissions;
    private final List<String> roles;

    public CustomUserDetails(User user, List<String> permissions, List<String> roles) {
        this.user = user;
        this.permissions = permissions != null ? permissions : List.of();
        this.roles = roles != null ? roles : List.of();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Convert permissions to GrantedAuthority objects
        // Format: "perm:USER_CREATE", "perm:USER_UPDATE", etc.
        List<GrantedAuthority> authorities = permissions.stream()
                .map(permission -> new SimpleGrantedAuthority("perm:" + permission))
                .collect(Collectors.toList());
        
        // Also add roles as authorities for backward compatibility
        authorities.addAll(roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList()));
        
        return authorities;
    }

    @Override
    public String getPassword() {
        return user.getPassword();
    }

    @Override
    public String getUsername() {
        return user.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !"I".equals(user.getStatus());
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return "A".equals(user.getStatus());
    }

    public User getUser() {
        return user;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public List<String> getRoles() {
        return roles;
    }
}

