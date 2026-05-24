package com.checkSheet.service;

import com.checkSheet.DAO.AppVersionDAO;
import com.checkSheet.DTO.AppVersionDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;


@Service
public class AppVersionServiceImpl implements AppVersionService {

//    @Autowired
//    private UtilityService utilityService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AppVersionDAO appVersionDAO;

    @Override
    public ResponseDTO<?> getAppVersionByOs(AppVersionDTO appVersionDTO) {
        try {
            AppVersionDTO appVerisonDetail = appVersionDAO.getAppVersionByOs(appVersionDTO.getOs());
            return new ResponseDTO<>("App version fetched successfully", appVerisonDetail);
        } catch(Exception e) {
            e.printStackTrace();
            return new ResponseDTO<>(false, e.getMessage());
        }
    }

}
