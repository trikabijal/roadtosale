package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.Date;
import java.util.List;
import java.util.Map;

@Getter
@Setter
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserDTO {
    private Long id;
    private String username;
    private String password;
    private String firstName;
    private String lastName;
    private String status;
    private String email;
    private Integer failLoginCount;
    private Date resendOtpTime;
    private Date lockTime;
    private Date createdAt;
    private Date updatedAt;
    private String accessToken;
    private String refreshToken;
    private String deviceType;
    private String mobile;
    private List<String> allRoles;
    private Map<String, String> roleNames;  // Maps role_code to role_name for display
    private List<String> menus;
    private List<String> permissions;
    private String roleCode;
    private List<Long> roleIds;
    private Long roleId;
    private Long departmentId;
    private List<Long> departmentIds;
    private List<Long> sectionIds;
    private List<String> permissionCodes;
    private Integer page;
    private Integer size;
    private String search;
    private String confirmPassword;

    //getUserById
    public UserDTO(Long id, String firstName, String lastName, String email, String username, String mobile) {
        this.id = id;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.username = username;
        this.mobile = mobile;
    }

    public void setUsername(String username) {
        this.username = username != null ? username.trim() : null;
    }

    public void setPassword(String password) {
        this.password = password != null ? password.trim() : null;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName != null ? firstName.trim() : null;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName != null ? lastName.trim() : null;
    }

    public void setStatus(String status) {
        this.status = status != null ? status.trim() : null;
    }

    public void setEmail(String email) {
        this.email = email != null ? email.trim() : null;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken != null ? accessToken.trim() : null;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken != null ? refreshToken.trim() : null;
    }

    public void setMobile(String mobile) {
        this.mobile = mobile != null ? mobile.trim() : null;
    }
}
