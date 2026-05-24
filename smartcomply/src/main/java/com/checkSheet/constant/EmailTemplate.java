package com.checkSheet.constant;

public enum EmailTemplate {
    NEW("Action Required: Prepare content for the checksheet [CHKS_NAME]","Dear [NAME],\n" +
            "Your action is required to prepare content for the checksheet. Kindly take a moment to complete this process.\n" +
            "You can access the checksheet via the following link:\n" +
            "[ENVIRONMENT_DOMAIN]/checksheet-management?waitingChks=true\n" +
            "If you are not logged in, please log in and navigate to the following menu:\n" +
            "Menu -> Master checksheet (under the label \"Waiting for Approver\").\n" +
            "Thank you for your prompt attention to this matter.\n" +
            "From, \n" +
            "[SENDER_NAMES]"),

    SUBMITTED("Action Required: Approval required for checksheet [CHKS_NAME] content","Dear [NAME],\n" +
            "Your approval is required to review and approve the content of the requested checksheet. Kindly take a moment to review and approve it at your earliest convenience.\n" +
            "You can access the checksheet via the following link:\n" +
            "[ENVIRONMENT_DOMAIN]/checksheet-management?waitingChks=true\n" +
            "If you are not logged in, please log in and navigate to the following menu:\n" +
            "Menu -> Master checksheet (under the label \"Waiting for Approver\").\n" +
            "Thank you for your prompt attention to this matter.\n" +
            "\n" +
            "From,\n" +
            "[SENDER_NAMES]"),

    INVALIDATED("Action Required: Checksheet  [CHKS_NAME]  content declined", "Dear [NAME],\n" +
            "Checksheet content has not been approved by the approver. Kindly update the checksheet content according to approver's remarks and submit it.\n" +
            "You can access the checksheet via the following link:\n" +
            "[ENVIRONMENT_DOMAIN]/checksheet-management?waitingChks=true\n" +
            "If you are not logged in, please log in and navigate to the following menu:\n" +
            "Menu -> Master checksheet (under the label \"Waiting for Approver\").\n" +
            "Thank you for your prompt attention to this matter.\n" +
            "From,\n" +
            "[SENDER_NAMES]"),

    VALIDATED("Action Required: Approval required for checksheet [CHKS_NAME] content", "Dear [NAME],\n" +
            "Your approval is required to review and approve the content of the requested checksheet. Kindly take a moment to review and approve it at your earliest convenience.\n" +
            "You can access the checksheet via the following link:\n" +
            "[ENVIRONMENT_DOMAIN]/checksheet-management?waitingChks=true\n" +
            "If you are not logged in, please log in and navigate to the following menu:\n" +
            "Menu -> Master checksheet (under the label \"Waiting for Approver\").\n" +
            "Thank you for your prompt attention to this matter.\n" +
            "\n" +
            "From,\n" +
            "[SENDER_NAMES]"),

    APPROVED("Checksheet  [CHKS_NAME] approved","Dear All,\n" +
            "Checksheet is ready to use for the operators.\n" +
            "You can access the checksheet via the following link:\n" +
            "[ENVIRONMENT_DOMAIN]/checksheet-management\n" +
            "If you are not logged in, please log in and navigate to the following menu:\n" +
            "Menu -> Master checksheet (under the label \"Status\").\n" +
            "Thank you for your prompt attention to this matter.\n" +
            "From,\n" +
            "[SENDER_NAMES]"),
    NOT_APPROVED("Action Required: Checksheet  [CHKS_NAME]  content declined", "Dear [NAME],\n" +
            "Checksheet content has not been approved by the approver. Kindly update the checksheet content according to approver's remarks and submit it.\n" +
            "You can access the checksheet via the following link:\n" +
            "[ENVIRONMENT_DOMAIN]/checksheet-management?waitingChks=true\n" +
            "If you are not logged in, please log in and navigate to the following menu:\n" +
            "Menu -> Master checksheet (under the label \"Waiting for Approver\").\n" +
            "Thank you for your prompt attention to this matter.\n" +
            "From,\n" +
            "[SENDER_NAMES]"),
    USER_CHKS_SUBMITTED("Action Required: Approval required for actual usage checksheet [CHKS_NAME]","Dear [NAME],\n" +
            "Your approval is required to review and approve the answers of the requested checksheet's parameters. Kindly take a moment to review and approve it at your earliest convenience.\n" +
            "You can access the checksheet via the following link:\n" +
            "[ENVIRONMENT_DOMAIN]/checksheet?waitingChks=true\n" +
            "If you are not logged in, please log in and navigate to the following menu:\n" +
            "Menu -> Actual use checksheet (under the label \"Waiting for Approver\").\n" +
            "Thank you for your prompt attention to this matter.\n" +
            "From,\n" +
            "[SENDER_NAMES]"),
    USER_CHKS_VALIDATED("Action Required: Approval required for actual usage checksheet [CHKS_NAME]","Dear [NAME],\n" +
            "Your approval is required to review and approve the answers of the requested checksheet's parameters. Kindly take a moment to review and approve it at your earliest convenience.\n" +
            "You can access the checksheet via the following link:\n" +
            "[ENVIRONMENT_DOMAIN]/checksheet?waitingChks=true\n" +
            "If you are not logged in, please log in and navigate to the following menu:\n" +
            "Menu -> Actual use checksheet (under the label \"Waiting for Approver\").\n" +
            "Thank you for your prompt attention to this matter.\n" +
            "From,\n" +
            "[SENDER_NAMES]"),
    USER_CHKS_INVALIDATED("Action Required: Posted Checksheet ([UID]) Declined", "Dear [NAME],\n" +
                        "Your posted item has been declined by the validator. Please refer to the validator&#39;s remarks\n" +
                        "and kindly create a new post with the updated details.\n" +
                        "Thank you for your cooperation."),
    USER_CHKS_NOT_APPROVED("Action Required: Posted Checksheet ([UID]) Declined","Dear Team,\n" +
            "Your posted Checksheet has not been approved by the approver. Please refer to the approver&#39;s\n" +
            "remarks and kindly create a new post with the updated details.\n" +
            "Thank you for your cooperation."),
    ALERT_NOT_OK_JUDGEMENTS(
        "Alert: NOT OK Judgements in Checksheet ([UID])",
        "Dear [Name],<br><br>" +
        "This is to inform you that the checksheet ([UID]) has been approved but contains NOT OK judgements.<br>" +
        "Please review the following judgement details:<br><br>"
    ),
    CREATE_DEPT_ADMIN("Assigning a department admin for [DEPT_NAME]","Dear [NAME],\n" +
            "You have assigned an admin to the [DEPT_NAME] department.\n" +
            "Now, you can create a section head for [DEPT_NAME] who will be responsible for creating section admin for the assigned department.\n" +
            "You can access the checksheet via the following link:\n" +
            "[ENVIRONMENT_DOMAIN]\n" +
            "From,\n" +
            "[SENDER_NAMES]"),
    CREATE_SECTION_ADMIN("Assigning a Section admin for [DEPT_NAME]","Dear [NAME],\n" +
            "You have assigned an admin to the [DEPT_NAME] section.\n" +
            "Now, you can create users for [DEPT_NAME] who will be responsible for creating checksheets for the assigned section.\n" +
            "You can access the checksheet via the following link:\n" +
            "[ENVIRONMENT_DOMAIN]\n" +
            "From,\n" +
            "[SENDER_NAMES]"),
    CREATE_SURPRISE_CHKS_FIELD("Concern for the Audit – [SURPRISE_CHKS_TITLE]","Dear [NAME],\n" +
            "\n" +
            "A concern has been assigned to you from [SRC_DEPT_NAME] for the audit [SURPRISE_CHKS_TITLE]. Please find the details below:\n" +
            "\n" +
            "Concern: [CONCERN]\n" +
            "Department: [DEPT_NAME]\n" +
            "Remarks: [REMARKS]\n" +
            "\n" +
            "Please review the attached images for reference. \n" +
            "\n" +
            "From,\n" +
            "[SENDER_NAMES]");
    private final String subject;
    private final String content;

    EmailTemplate(String subject, String content) {
        this.subject = subject;
        this.content = content;
    }

    public String getSubject() {
        return subject;
    }

    public String getContent() {
        return content;
    }
}
