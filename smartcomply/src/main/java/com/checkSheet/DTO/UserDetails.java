package com.checkSheet.DTO;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.Date;

@Getter
@Setter
@Builder
@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserDetails {
    private Long id;
    private String username;
    private String password;
    private String firstName;
    private String lastName;
    private String status;
    private String email;
    private String fullName;
    private String ipn;
    private String mailAddress;
    private String authStatus;
    private Integer failLoginCount;
    private Date resendOtpTime;
    private Date lockTime;
    private Date createdAt;
    private Date updatedAt;
    private String accessToken;
    private String refreshToken;
}
