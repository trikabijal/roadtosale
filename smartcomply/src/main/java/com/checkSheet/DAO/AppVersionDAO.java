package com.checkSheet.DAO;

import com.checkSheet.DTO.AppVersionDTO;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

@Repository
public class AppVersionDAO {

    @Autowired
    private EntityManager entityManager;

    public AppVersionDTO getAppVersionByOs(String osName) {
        try {
            String query = "SELECT av.id, av.version, av.url, av.os, av.is_forcefully_update, av.version_name FROM app_versions av " +
                    " WHERE lower(av.os) = '" + osName.toLowerCase() + "' order by id desc limit 1";
            AppVersionDTO result = (AppVersionDTO) entityManager.createNativeQuery(query, "getAppVersionByOs").getSingleResult();
            return result;
        } catch (Exception e) {
            e.printStackTrace();
            return new AppVersionDTO();
        }
    }
}
