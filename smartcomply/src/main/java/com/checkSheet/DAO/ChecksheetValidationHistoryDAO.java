package com.checkSheet.DAO;

import com.checkSheet.DTO.ChecksheetValidationDTO;
import com.checkSheet.DTO.ChecksheetValidationHistoryDTO;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ChecksheetValidationHistoryDAO {

    @Autowired
    private EntityManager entityManager;

    public List<ChecksheetValidationHistoryDTO> getChecksheetValidatorHistoryByChecksheetId(Long checksheetId) {
        try {
            String nativeQuery = "SELECT cvh.id, cvh.remarks, cvh.status, cvh.validator_user_id, cvh.validated_at, cvh.version, u.first_name, u.last_name, u.id as userId FROM " +
                    " (select cvh.* from checksheet_validations_history cvh where cvh.checksheet_id = '" + checksheetId + "') cvh join " +
                    " (select u.* from users u ) u on u.id = cvh.validator_user_id order by cvh.validator_user_id asc, cvh.version desc ";

            List<ChecksheetValidationHistoryDTO> getChecksheetValidatorHistoryByChecksheetId = (List<ChecksheetValidationHistoryDTO>) entityManager.createNativeQuery(nativeQuery, "getChecksheetValidatorHistoryByChecksheetId")
                    .getResultList();
            return getChecksheetValidatorHistoryByChecksheetId;
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }
}
