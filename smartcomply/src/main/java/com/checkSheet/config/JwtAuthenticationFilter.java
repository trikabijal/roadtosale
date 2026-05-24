package com.checkSheet.config;

import com.checkSheet.DAO.UserRoleDepartmentDAO;
import com.checkSheet.entity.User;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.PermissionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.checkSheet.repository.UserRepository;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final UserRepository userRepository;
    private final UserRoleDepartmentDAO userRoleDepartmentDAO;
    private final PermissionService permissionService;
    private final Map<String, List<String>> endpointPermissionMap;
    private final EndpointRegistry endpointRegistry;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            String requestURI = request.getRequestURI();

            // Check if endpoint exists
//            if (!endpointRegistry.isEndpointExists(requestURI)) {
//                sendErrorResponse(response, HttpServletResponse.SC_NOT_FOUND, "Endpoint not found");
//                return;
//            }

            // Check if endpoint is whitelisted
//            if (isWhitelisted(requestURI)) {
//                filterChain.doFilter(request, response);
//                return;
//            }

            // JWT Authentication
            String authHeader = request.getHeader("Authorization");
            if (!isValidAuthHeader(authHeader)) {
                filterChain.doFilter(request, response);
                return;
            }

            processAuthentication(request, response, filterChain, authHeader);

        } catch (Exception e) {
            e.printStackTrace();
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
        }
    }

    private boolean isValidAuthHeader(String authHeader) {
        return authHeader != null && authHeader.startsWith("Bearer ");
    }

    private void processAuthentication(HttpServletRequest request, HttpServletResponse response, 
            FilterChain filterChain, String authHeader) throws IOException, ServletException {
        String jwt = authHeader.substring(7);
        String userEmail = jwtService.extractUsername(jwt);

        if (userEmail != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            Optional<User> loginUser = userRepository.findByUsernameIgnoreCase(userEmail);
            if (!loginUser.isPresent()) {
                sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED, "User not found");
                return;
            }

            // Extract permissions and roles from JWT token
            List<String> permissions = jwtService.extractPermissions(jwt);
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) jwtService.extractClaim(jwt, claims -> claims.get("roles"));

            // Fallback: If permissions not in token, load from database
            if (permissions == null || permissions.isEmpty()) {
                permissions = loadPermissionsFromDatabase(loginUser.get().getId());
            }
            
            // Fallback: If roles not in token, load from database
            if (roles == null || roles.isEmpty()) {
                roles = userRoleDepartmentDAO.getUserByEmailAndUserId(loginUser.get().getId());
            }

            // Create CustomUserDetails with permissions
            CustomUserDetails userDetails = new CustomUserDetails(loginUser.get(), permissions, roles);

            // Check if the endpoint requires specific permissions
            String requestURI = request.getRequestURI();
            if (!isAuthorizedForEndpoint(requestURI, permissions)) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                String jsonResponse = "{\"status\": false, \"message\": \"You do not have permission to access this resource\"}";
                response.getWriter().write(jsonResponse);
                return;
            }

            if (jwtService.isTokenValid(jwt, userDetails)) {
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities() // Now includes permissions as "perm:PERMISSION_CODE"
                );
                authToken.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request)
                );
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }
        filterChain.doFilter(request, response);
    }

    private List<String> loadPermissionsFromDatabase(Long userId) {
        try {
            Set<String> permissions = permissionService.getEffectivePermissionsForUser(userId);
            return new java.util.ArrayList<>(permissions);
        } catch (CustomException e) {
            e.printStackTrace();
            return Collections.emptyList();
        } catch (Exception e) {
            e.printStackTrace();
            return Collections.emptyList();
        }
    }

    private void sendErrorResponse(HttpServletResponse response, int status, String message) 
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(String.format(
            "{\"status\": false, \"message\": \"%s\"}", message));
    }

    private boolean isAuthorizedForEndpoint(String requestURI, List<String> userPermissions) {
        // If endpoint not in map, allow access (no specific permission required)
        if (!endpointPermissionMap.containsKey(requestURI)) {
            return true;
        }
        
        // Get required permissions for this endpoint
        List<String> requiredPermissions = endpointPermissionMap.get(requestURI);
        
        // User must have at least one of the required permissions (OR logic)
        return !Collections.disjoint(userPermissions, requiredPermissions);
    }
}
