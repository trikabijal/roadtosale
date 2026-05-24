package com.checkSheet.service;

import org.springframework.transaction.annotation.Transactional;

import com.checkSheet.DTO.UserDTO;
import com.checkSheet.DTO.UserDetails;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;

public interface UserService {
    UserDetails findUserByIpn(String ipn, String pwd) throws Exception;

    ResponseDTO<?> register(UserDTO userDTO) throws CustomException;

    ResponseDTO<?> createOrEditOperator(UserDTO userDTO) throws CustomException;

    ResponseDTO<?> deleteUser(UserDTO userDTO) throws CustomException;

    ResponseDTO<?> getOperator(UserDTO userDTO) throws CustomException;

    ResponseDTO<?> getAllOperators(UserDTO userDTO) throws CustomException;

    ResponseDTO<?> getChecksheetPreparers(UserDTO userDTO) throws CustomException;

    ResponseDTO<?> getChecksheetValidators(UserDTO userDTO) throws CustomException;

    ResponseDTO<?> getChecksheetApprovers(UserDTO userDTO) throws CustomException;

    ResponseDTO<?> getDataValidators(UserDTO userDTO) throws CustomException;

    ResponseDTO<?> getDataApprovers(UserDTO userDTO) throws CustomException;

    ResponseDTO<?> getChecksheetOperators(UserDTO userDTO) throws CustomException;

    ResponseDTO<?> getAlertUsers(UserDTO userDTO) throws CustomException;

    ResponseDTO<?> getEscalationUsers(UserDTO userDTO) throws CustomException;

    ResponseDTO<?> login(UserDTO userDTO) throws CustomException;

    @Transactional
    ResponseDTO<?> validateAndSaveUsername(UserDTO userDTO) throws CustomException;

    UserDTO refreshToken(UserDTO userDTO) throws CustomException;

    ResponseDTO<?> updatePassword(UserDTO userDTO) throws CustomException;

    ResponseDTO<?> resetPassword(UserDTO userDTO) throws CustomException;
}
