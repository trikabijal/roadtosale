package com.checkSheet.service;

import com.checkSheet.DTO.UserDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;

public interface RefreshTokenService {
    String generateRefreshToken(String username, Boolean isNewLogin, String deviceType) throws CustomException;
}
