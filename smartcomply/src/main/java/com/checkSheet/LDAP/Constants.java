package com.checkSheet.LDAP;


public class Constants {

    /** LDAP Details */
    // public static final String ldapURI =
    // "ldap://annope01.mc2.renault.fr:389/ou=RNTBCI,ou=People,o=renault";
    public static final String ldapURI = "ldap://annope01.mc2.renault.fr:389/ou=People,o=renault";
    // public static final String ldapURI =
    // "ldap://annope01.mc2.renault.fr:389/o=renault";
    public static final String contextFactory = "com.sun.jndi.ldap.LdapCtxFactory";

    /** Constant Success */
    public static final String SUCCESS = "SUCCESS";

    /** Constant fail */
    public static final String FAIL = "FAILED";

    /** Constant Not found */
    public static final String NOT_FOUND = "NOTFOUND";

    /** Constant Internal server error */

    public static final String MSG_INTERNAL_SERVER_ERROR = "Internal server error";

    /** Constant error code */
    public static final String ERR_CODE_500 = "500";

    public static final String OUTPUT_ID_NOT_FOUND = "OUTPUT ID NOT FOUND";

    public static final String MODEL_AND_ENGINE_NUM_EXIST = "MODEL AND ENGINE NUM EXIST";

    public static final String REWORK_ALREADY_COMPLETED = "REWORK ALREADY COMPLETED";

    public static final String INPUT_ALREADY_COMPLETED = "INPUT ALREADY COMPLETED";

    public static final String RECORD_SAVED = "RECORD_SAVED";

    public static final String RECORD_UPDATED = "RECORD_UPDATED";

    public static final String NO_ENGINE_MODEL_EXISTS = "NO ENGINE MODEL EXISTS";

    public static final String NO_OUTPUT_FOUND = "NO OUTPUT FOUND";

    public static final String REWORK_NOT_AVAILABLE = "REWORK ENGINE NUMBER NOT AVAILABLE";

    public static final String QA_NOT_VALIDATED = "QA NOT VALIDATED";

    public static final String NEW_OUTPUT_ENTRY_ALLOWED = "NEW OUTPUT ENTRY ALLOWED";

    public static final String NO_MODEL_ID_EXIST = "NO MODEL ID EXIST";

    public static final String NO_PARTS_MATCH_WITH_MODEL = "NO PART MATCH WITH MODEL";

    public static final int OUTPUT_COMPLETED = 1;

    public static final int REWORK_NOT_COMPLETED = 2;

    public static final int REWORK_COMPLETED = 3;

    public static final int QA_APPROVED = 4;

    public static final int INPUT_COMPLETED = 5;

    public static final int QA_ROLE = 6;

    public static final int CORNCERN_RAISER_ROLE = 4;

    public static final String FROM = "nstr.repairstation1@rnaipl.com";

    public static final String TO = "hemalatha.kabali@rntbci.com";

    public static final int IN_PROGRESS = 1;

    public static final int OPEN = 2;

    public static final int UNDER_VALIDATION = 3;

    public static final int CLOSED = 4;

    public static final int REJECTED = 5;

    public static final String FY= "FY";

    public static final String NSTR= "-NSTR-";

    public static final String NO_DATA_FOUND = "NO DATA FOUND";

    public static final String COM1 = "COM1";

    public static final String OUTPUT_COMPLETED_VALUE = "output completed";

    public static final String REWORK_NOT_COMPLETED_VALUE = "rework not completed";

    public static final String REWORK_COMPLETED_VALUE = "rework completed";

    public static final String QA_COMPLETED_VALUE = "qa completed";

    public static final String OTHERS_VALUE = "Others";

    public static final String DEV_DOCS_PATH = "http://cq161ora/docs/";

    public static final String PROD_DOCS_PATH = "http://cqm177ora/docs/";

    public static final String DEV_PATH = "http://cq161ora/nstr/";

    public static final String PROD_PATH = "http://cqm177ora/nstr/";
}
