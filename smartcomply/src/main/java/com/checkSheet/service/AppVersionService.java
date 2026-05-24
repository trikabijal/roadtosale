package com.checkSheet.service;

import com.checkSheet.DTO.AppVersionDTO;
import com.checkSheet.DTO.response.ResponseDTO;

public interface AppVersionService {

    ResponseDTO<?> getAppVersionByOs(AppVersionDTO appVersionDTO);
}
