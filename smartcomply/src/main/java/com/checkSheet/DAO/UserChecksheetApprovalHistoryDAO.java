package com.checkSheet.DAO;

import com.checkSheet.DTO.ChecksheetApprovalHistoryDTO;
import com.checkSheet.DTO.UserChecksheetApprovalHistoryDTO;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class UserChecksheetApprovalHistoryDAO {

    @Autowired
    private EntityManager entityManager;

    public List<UserChecksheetApprovalHistoryDTO> getUserChecksheetApproverHistoryByInspectionId(Long checksheetId) {
        try {
            String nativeQuery = "SELECT ucah.id, ucah.remarks, ucah.status, ucah.data_approver_user_id, ucah.approved_at, ucah.version, u.first_name, u.last_name FROM " +
                    " (select ucah.* from user_checksheet_approvals_history ucah where ucah.inspection_id = '" + checksheetId + "') ucah join " +
                    " (select u.* from users u ) u on u.id = ucah.data_approver_user_id order by ucah.data_approver_user_id asc, ucah.version desc ";

            List<UserChecksheetApprovalHistoryDTO> getChecksheetValidatorHistoryByChecksheetId =
                    (List<UserChecksheetApprovalHistoryDTO>) entityManager.createNativeQuery(nativeQuery, "getUserChecksheetApproverHistoryByInspectionId")
                    .getResultList();
            return getChecksheetValidatorHistoryByChecksheetId;
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }

    public Object getUsrChksApprovalhistory(Long userChecksheetId) {
        try {
            String nativeQuery = "SELECT ucah.id, ucah.inspection_id, ucah.remarks, ucah.status, ucah.approved_at, ucah.version, u.first_name, u.last_name FROM " +
                    " (select ucah.* from user_checksheet_approvals_history ucah where ucah.inspection_id = '" + userChecksheetId + "') ucah join " +
                    " (select u.* from users u ) u on u.id = ucah.data_approver_user_id order by ucah.data_approver_user_id asc, ucah.version desc ";

            List<UserChecksheetApprovalHistoryDTO> getChecksheetValidatorHistoryByChecksheetId =
                    (List<UserChecksheetApprovalHistoryDTO>) entityManager.createNativeQuery(nativeQuery, "getUsrChksApprovalhistory")
                            .getResultList();
            return getChecksheetValidatorHistoryByChecksheetId;
        } catch (Exception e) {
            e.printStackTrace();
            return List.of();
        }
    }
}
