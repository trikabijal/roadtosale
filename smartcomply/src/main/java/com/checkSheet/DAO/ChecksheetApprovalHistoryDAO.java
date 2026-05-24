package com.checkSheet.DAO;

import com.checkSheet.DTO.ChecksheetApprovalHistoryDTO;
import com.checkSheet.DTO.ChecksheetValidationDTO;
import com.checkSheet.DTO.ChecksheetValidationHistoryDTO;
import com.checkSheet.entity.ChecksheetApprovalHistory;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class ChecksheetApprovalHistoryDAO {

    @Autowired
    private EntityManager entityManager;

    public List<ChecksheetApprovalHistoryDTO> getChecksheetApproverHistoryByChecksheetId(Long checksheetId) {
        try {
            String nativeQuery = "SELECT cah.id, cah.remarks, cah.status, cah.approver_user_id, cah.approved_at, cah.version, u.first_name, u.last_name FROM " +
                    " (select cah.* from checksheet_approvals_history cah where cah.checksheet_id = '" + checksheetId + "') cah join " +
                    " (select u.* from users u ) u on u.id = cah.approver_user_id order by cah.approver_user_id asc, cah.version desc ";

            List<ChecksheetApprovalHistoryDTO> getChecksheetValidatorHistoryByChecksheetId =
                    (List<ChecksheetApprovalHistoryDTO>) entityManager.createNativeQuery(nativeQuery, "getChecksheetApproverHistoryByChecksheetId")
                    .getResultList();
            return getChecksheetValidatorHistoryByChecksheetId;
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }

}
