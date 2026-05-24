package com.checkSheet.DAO;

import com.checkSheet.DTO.ChecksheetValidationDTO;
import com.checkSheet.DTO.UserChecksheetValidationDTO;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class UserChecksheetValidationDAO {

    @Autowired
    private EntityManager entityManager;

    public List<UserChecksheetValidationDTO> getChecksheetValidatorByChecksheetId(Long checksheetId) {
        try {
            String nativeQuery = "SELECT cv.id, cv.remarks, cv.status, cv.data_validator_user_id, cv.validated_at, u.first_name, u.last_name FROM " +
                    " (select cv.* from user_checksheet_validations cv where checksheet_id = '" + checksheetId + "') cv join " +
                    " (select u.* from users u ) u on u.id = cv.validator_user_id ";

            return (List<UserChecksheetValidationDTO>) entityManager.createNativeQuery(nativeQuery, "getChecksheetValidatorByChecksheetId")
                    .getResultList();
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }
}
