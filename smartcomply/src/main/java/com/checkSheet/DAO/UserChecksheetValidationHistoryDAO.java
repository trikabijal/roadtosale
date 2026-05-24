package com.checkSheet.DAO;

import com.checkSheet.DTO.UserChecksheetValidationHistoryDTO;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class UserChecksheetValidationHistoryDAO {

    @Autowired
    private EntityManager entityManager;

    public List<UserChecksheetValidationHistoryDTO> getUserChecksheetValidatorHistoryByInspectionId(Long checksheetId) {
        try {
            String nativeQuery = "SELECT ucvh.id, ucvh.remarks, ucvh.status, ucvh.data_validator_user_id, ucvh.validated_at, ucvh.version, u.first_name, u.last_name FROM " +
                    " (select ucvh.* from user_checksheet_validations_history ucvh where ucvh.inspection_id = '" + checksheetId + "') ucvh join " +
                    " (select u.* from users u ) u on u.id = ucvh.data_validator_user_id order by ucvh.data_validator_user_id asc, ucvh.version desc ";

            List<UserChecksheetValidationHistoryDTO> getChecksheetValidatorHistoryByChecksheetId =
                    (List<UserChecksheetValidationHistoryDTO>) entityManager.createNativeQuery(nativeQuery, "getUserChecksheetValidatorHistoryByInspectionId")
                    .getResultList();
            return getChecksheetValidatorHistoryByChecksheetId;
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }

//    getUsrChksValidationhistory -- For Tablet
    public List<UserChecksheetValidationHistoryDTO> getUsrChksValidationhistory(Long userChecksheetId) {
        try {
            String nativeQuery = "SELECT ucvh.id, ucvh.inspection_id, ucvh.remarks, ucvh.status, ucvh.validated_at, ucvh.version, u.first_name, u.last_name FROM " +
                    " (select ucvh.* from user_checksheet_validations_history ucvh where ucvh.inspection_id = '" + userChecksheetId + "') ucvh join " +
                    " users u on u.id = ucvh.data_validator_user_id order by ucvh.data_validator_user_id asc, ucvh.version desc ";

            List<UserChecksheetValidationHistoryDTO> getChecksheetValidatorHistoryByChecksheetId =
                    (List<UserChecksheetValidationHistoryDTO>) entityManager.createNativeQuery(nativeQuery, "getUsrChksValidationhistory")
                            .getResultList();
            return getChecksheetValidatorHistoryByChecksheetId;
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }
}
