package com.checkSheet.service;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import com.checkSheet.entity.*;
import com.checkSheet.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.amazonaws.services.mq.model.BadRequestException;
import com.checkSheet.DAO.UserDAO;
import com.checkSheet.DAO.UserRoleDepartmentDAO;
import com.checkSheet.DTO.UserDTO;
import com.checkSheet.DTO.UserDetails;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.LDAP.Constants;
import com.checkSheet.LDAP.LdapAuth;
import com.checkSheet.config.JwtService;
import com.checkSheet.constant.SystemRole;
import com.checkSheet.constant.UserDropdownPermissionGroups;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.Email.EmailService;

@Service
public class UserServiceImpl implements UserService {

    @Value("${user.otpCount:3}")
    private int totalLoginCount = 3;

    @Value("${user.resendOTPTime:10}")
    private int resendOTPTime = 10;

    @Value("${ldap.environment:}")
    private String ldapEnvironment;

    @Autowired
    private EmailService emailService;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private UserRoleDepartmentRepository userRoleDepartmentRepository;

    @Autowired
    private UserDAO userDAO;

    @Autowired
    private UserRoleDepartmentDAO userRoleDepartmentDAO;

    static List<String> superAdminMenus = new ArrayList<>();
    static List<String> departmentAdminMenus = new ArrayList<>();
    static List<String> subDepartmentAdminMenus = new ArrayList<>();
    static List<String> checksheetPrepareMenus = new ArrayList<>();
    static List<String> checksheetValidationAndApprovalMenus = new ArrayList<>();
    static List<String> checksheetDataValidationMenus = new ArrayList<>();
    static List<String> checksheetDataApprovalMenus = new ArrayList<>();


    static List<String> superAdminPermissions = new ArrayList<>();
    static List<String> departmentAdminPermissions = new ArrayList<>();
    static List<String> subDepartmentAdminPermissions = new ArrayList<>();
    static List<String> checksheetPreparerPermissions = new ArrayList<>();
    static List<String> checksheetValidatorPermissions = new ArrayList<>();
    static List<String> checksheetApproverPermissions = new ArrayList<>();
    static List<String> checksheetDataValidatorPermissions = new ArrayList<>();
    static List<String> checksheetDataApproverPermissions = new ArrayList<>();
    static List<String> operatorPermissions = new ArrayList<>();

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private DepartmentRepository departmentRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private UtilityService utilityService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private PermissionService permissionService;

    @Value("${app.jwtRefreshTokenExpiration:3600000}")
    private Long jwtRefreshTokenExpirationInMs = 3600000L;



    /**
     * validates the user and return user object
     *
     * @param ipn
     * @param pwd
     * @return
     * @throws Exception
     */
    @Override
    public UserDetails findUserByIpn(String ipn, String pwd) throws Exception {
        byte[] decodedBytes = Base64.getUrlDecoder().decode(pwd);
        String decodedPwd = new String(decodedBytes);
        UserDetails userDetails = LdapAuth.checkWithLDAP(ipn, decodedPwd);
        if ((Constants.SUCCESS).equals(userDetails.getAuthStatus())) {
//            Optional<User> user = userRepository.findUserByIpn(ipn.toUpperCase());
//            if (user.isPresent()) {
//                userDetails.setId(user.get().getId());
//                userDetails.setRoles(user.get().getRole());
//                // userDetails.setDept(user.get().getDept());
//            } else {
//                userDetails.setAuthStatus(Constants.NOT_FOUND);
//            }
        }
        return userDetails;
    }

//    public UserServiceImpl(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService, AuthenticationManager authenticationManager) {
//        this.userRepository = userRepository;
//        this.passwordEncoder = passwordEncoder;
//        this.jwtService = jwtService;
//        this.authenticationManager = authenticationManager;
//    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> register(UserDTO userDTO) throws CustomException {
        Optional<User> tempUser = userRepository.findByEmail(userDTO.getEmail());
        if(tempUser.isPresent()) {
            throw new CustomException("Email already exists", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        User user = new User();
        user.setFirstName(userDTO.getFirstName());
        user.setLastName(userDTO.getLastName());
        user.setEmail(userDTO.getEmail());
        user.setPassword(passwordEncoder.encode(userDTO.getPassword()));
        userRepository.save(user);
        var jwtToken = jwtService.generateToken(user);
        user.setJwt_token(jwtToken);
        userRepository.save(user);
        return new ResponseDTO<>(true, "User registered successfully", UserDTO.builder()
                .accessToken(jwtToken)
                .build());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> createOrEditOperator(UserDTO userDTO) throws CustomException {
        try {
            // Get current logged in user first
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String username = authentication.getName();
            Optional<User> loginUser = userRepository.findByUsernameIgnoreCase(username);
            
            if((Objects.equals(userDTO, null) ||
                Objects.equals(userDTO.getUsername(), null) || userDTO.getUsername().trim().isEmpty() ||
                Objects.equals(userDTO.getFirstName(), null) || userDTO.getFirstName().trim().isEmpty())
//                Objects.equals(userDTO.getLastName(), null) || userDTO.getLastName().trim().isEmpty())
                && Objects.equals(userDTO.getId(), null)) {
                throw new CustomException("Please provide email, firstname", HttpStatus.UNPROCESSABLE_ENTITY);
            }else if(
                Objects.equals(userDTO.getDepartmentId(),null) &&
                (userDTO.getDepartmentIds() == null || userDTO.getDepartmentIds().isEmpty()) &&
                (userDTO.getSectionIds() == null || userDTO.getSectionIds().isEmpty()) &&
                !permissionService.hasPermission(loginUser.get().getId(), "SUBDEPARTMENT_CREATE")
            ){
                throw new CustomException("Please provide section", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if((Objects.equals(userDTO, null) ||
                    Objects.equals(userDTO.getFirstName(), null) || userDTO.getFirstName().trim().isEmpty())
                    && !Objects.equals(userDTO.getId(), null)) {
                throw new CustomException("Please provide firstname", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            if((Objects.equals(userDTO, null) ||
                    Objects.equals(userDTO.getMobile(), null) || userDTO.getMobile().trim().isEmpty())
                    && !Objects.equals(userDTO.getId(), null)) {
                throw new CustomException("Please provide mobile", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            User user = new User();
            if(!Objects.equals(userDTO.getId(), null)) {
                Optional<User> userById = userRepository.findById(userDTO.getId());
                if(!userById.isPresent()) {
                    throw new CustomException("Please provide valid id", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                user = userById.get();
                user.setUpdatedBy(loginUser.get());
            } else {
                Optional<User> userByUsername = userRepository.findByUsernameIgnoreCase(userDTO.getUsername());
                if(userByUsername.isPresent()) {
                    user = userByUsername.get();
                    user.setUpdatedBy(loginUser.get());
                    user.setUpdatedAt(new Date());
                } else {
                    user.setCreatedBy(loginUser.get());
                }
                user.setUsername(userDTO.getUsername().toUpperCase());
            }
            user.setFirstName(userDTO.getFirstName());
            user.setLastName(userDTO.getLastName());
            user.setMobile(userDTO.getMobile());
            user.setEmail(userDTO.getEmail());
            user.setPassword(passwordEncoder.encode(userDTO.getUsername().trim().replaceAll("\\s+", "").toUpperCase()));
            User savedUser = userRepository.save(user);
            boolean isNewUser = Objects.equals(userDTO.getId(), null);
            if(isNewUser) {
                userDTO.setId(savedUser.getId());
            }

            // --- Multiple Roles & Departments Logic Start ---

            // 1. Prepare list of roles to be assigned
            List<Long> targetRoleIds = resolveTargetRoleIds(userDTO);

            if (targetRoleIds.isEmpty()) {
                throw new CustomException("Please provide role", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // 1.1 Prepare list of Departments/Sections to be assigned
            Set<Long> targetDepartmentIds = new HashSet<>();
            if (userDTO.getDepartmentId() != null) {
                targetDepartmentIds.add(userDTO.getDepartmentId());
            }
            if (userDTO.getDepartmentIds() != null) {
                targetDepartmentIds.addAll(userDTO.getDepartmentIds());
            }
            if (userDTO.getSectionIds() != null) {
                targetDepartmentIds.addAll(userDTO.getSectionIds());
            }
            
            // If No departments provided (and not DEPT_ADMIN who might imply something else? Or usually required)
            // Original code threw error if departmentId null.
            // If targetDepartmentIds isEmpty -> userDTO.getDepartmentId() was null or empty list & others empty.
            // Handled by validation above, but double check.
           // Allow empty departments for users with DEPARTMENT_CREATE or SUBDEPARTMENT_CREATE permissions
           boolean canCreateWithoutDepartment = permissionService.hasPermission(loginUser.get().getId(), "DEPARTMENT_CREATE") ||
                                                  permissionService.hasPermission(loginUser.get().getId(), "SUBDEPARTMENT_CREATE");
           if (targetDepartmentIds.isEmpty() && !canCreateWithoutDepartment) {
               // Assuming Super Admin might be created without department? Or Dept Admin.
               // Re-using the message
                throw new CustomException("Please provide section", HttpStatus.UNPROCESSABLE_ENTITY);
           }
           
           // If targetDepartments is empty but allow (e.g. SUPER_ADMIN), adding checks below to avoid loops

            // 2. Fetch all existing UserRoleDepartments for the user (for Sync/Update)
            List<UserRoleDepartment> existingUserRoles = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(savedUser.getId());
            if (existingUserRoles == null) {
                existingUserRoles = new ArrayList<>();
            }
            // Map Valid Key (RoleId + DeptId) -> UserRoleDepartment
            // Use a String key "roleId-deptId"
            Map<String, UserRoleDepartment> existingUrdMap = new HashMap<>();
            for (UserRoleDepartment urd : existingUserRoles) {
                if (urd.getRole() != null && urd.getDepartmentId() != null) {
                    existingUrdMap.put(urd.getRole().getId() + "-" + urd.getDepartmentId().getId(), urd);
                }
            }
            
            // fetch logged in user info for permission checks
             List<UserRoleDepartment> loggedInUserRoleDepartments = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(loginUser.get().getId());
             String loggedInUserRoleCode = null;
             if (loggedInUserRoleDepartments != null && !loggedInUserRoleDepartments.isEmpty()) {
                 loggedInUserRoleCode = loggedInUserRoleDepartments.get(0).getRole().getRoleCode();
             }
             
             List<Long> loggedInUserSections = new ArrayList<>();
             // Check if user has DEPARTMENT_CREATE permission (similar access as SUPER_ADMIN)
             boolean hasDepartmentCreatePermission = permissionService.hasPermission(loginUser.get().getId(), "DEPARTMENT_CREATE");
             if (!hasDepartmentCreatePermission) {
                  Optional<Role> roleByRoleCode = roleRepository.findByRoleCode(loggedInUserRoleCode);
                  if (roleByRoleCode.isPresent()) {
                     List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUserIdAndRoleId(loginUser.get().getId(), roleByRoleCode.get().getId());
                     if (userRoleByUserIdDepartment != null) {
                        loggedInUserSections = userRoleByUserIdDepartment.stream()
                             .filter(urd -> urd.getDepartmentId() != null)
                             .map(urd -> urd.getDepartmentId().getId())
                             .collect(Collectors.toList());
                     }
                  }
             }

            // 3. Process Cartesian Product (Target Roles x Target Departments)
            Set<String> processedKeys = new HashSet<>();

            for (Long targetRoleId : targetRoleIds) {
                Optional<Role> roleOpt = roleRepository.findById(targetRoleId);
                if (!roleOpt.isPresent()) {
                    throw new CustomException("Invalid role id: " + targetRoleId, HttpStatus.UNPROCESSABLE_ENTITY);
                }
                Role role = roleOpt.get();

                for (Long targetDeptId : targetDepartmentIds) {
                     processedKeys.add(targetRoleId + "-" + targetDeptId);

                     // Permission Check (Is logged in user allowed to assign for this department?)
                     // Skipping for users with DEPARTMENT_CREATE permission
                     if (!hasDepartmentCreatePermission) {
                         // If not super admin, check if targetDeptId is in logged in user's scope
                         if (!loggedInUserSections.contains(targetDeptId)) {
                             // Option: Skip or Throw.
                             // Throwing ensures security.
                             throw new CustomException("You do not have permission for section ID: " + targetDeptId, HttpStatus.UNPROCESSABLE_ENTITY);
                         }
                     }

                    // Check if mapping exists
                    String key = targetRoleId + "-" + targetDeptId;
                    if (existingUrdMap.containsKey(key)) {
                        // Already exists and active.
                        continue; 
                    }
                    
                    // Check soft deleted or create new
                    Department selectedDept = departmentRepository.findById(targetDeptId)
                            .orElseThrow(() -> new CustomException("Invalid Department Id: " + targetDeptId, HttpStatus.UNPROCESSABLE_ENTITY));
                            
                    Optional<UserRoleDepartment> isExistUserRoleDepartment = userRoleDepartmentRepository.findByUser_IdAndRole_IdAndDepartmentId_Id(savedUser.getId(), targetRoleId, targetDeptId);
                    
                    UserRoleDepartment userRoleDepartment;
                    if (isExistUserRoleDepartment.isPresent()) {
                        userRoleDepartment = isExistUserRoleDepartment.get();
                        if (userRoleDepartment.getDeletedBy() != null) {
                             userRoleDepartment.setDeletedBy(null);
                             userRoleDepartment.setDeletedAt(null);
                             userRoleDepartmentRepository.save(userRoleDepartment);
                        }
                    } else {
                        userRoleDepartment = new UserRoleDepartment();
                        userRoleDepartment.setUser(savedUser);
                        userRoleDepartment.setRole(role);
                        userRoleDepartment.setDepartmentId(selectedDept);
                        userRoleDepartmentRepository.save(userRoleDepartment);
                    }
                }
            }

            // 4. Handle Removals (Sync)
            // If the request is for specific roles/departments, do we remove others?
            // "Sync" implies we set the state to exactly what is provided. 
            // However, complicating factor: If user provides Role A and Departments X, Y.
            // And user previously had Role B, Department Z.
            // Should Role B/Z be removed?
            // Usually "edit" implies full state update or partial.
            // Given the logic gathers "existingUserRoles", it suggests a sync.
            
            // Strategy: 
            // If the user is being "Created", we just add.
            // If "Edited", we usually replace.
            // Safest Sync Logic:
            // Remove any (Role, Dept) tuple that is NOT in the new set, 
            // BUT only if the logged-in user HAS permission to that Dept (otherwise they can't see/delete it).
            // AND only if the Role is one of variable types (maybe?). Assuming all roles handled here.
            
            for (UserRoleDepartment existingURD : existingUserRoles) {
                if (existingURD.getDepartmentId() == null || existingURD.getRole() == null) continue;
                
                String key = existingURD.getRole().getId() + "-" + existingURD.getDepartmentId().getId();
                
                if (!processedKeys.contains(key)) {
                    // Candidate for deletion.
                    
                    // Security Check: Can logged in user 'see'/'manage' this department?
                    if (!Objects.equals(loggedInUserRoleCode, SystemRole.SUPER_ADMIN)) {
                         if (!loggedInUserSections.contains(existingURD.getDepartmentId().getId())) {
                             // If I can't see it, I shouldn't delete it. Keep it.
                             continue;
                         }
                    }
                    
                    // Soft Delete
                    existingURD.setDeletedBy(loginUser.get());
                    existingURD.setDeletedAt(new Date());
                    userRoleDepartmentRepository.save(existingURD);
                }
            }

            UserDTO responseUserDTO = buildCreatedOrUpdatedUserDTO(savedUser, targetRoleIds, targetDepartmentIds);

            if(isNewUser) {
                 emailService.sendEmail(userDTO.getEmail(), "Checksheet User Verification", "", null, null, null);
                 return new ResponseDTO<>(true, "User created successfully", responseUserDTO);
            } else {
                 return new ResponseDTO<>(true, "User updated successfully", responseUserDTO);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private List<Long> resolveTargetRoleIds(UserDTO userDTO) throws CustomException {
        List<Long> targetRoleIds = new ArrayList<>();

        if (userDTO.getRoleIds() != null && !userDTO.getRoleIds().isEmpty()) {
            targetRoleIds.addAll(userDTO.getRoleIds());
        } else if (userDTO.getRoleId() != null) {
            targetRoleIds.add(userDTO.getRoleId());
        } else if (StringUtils.hasText(userDTO.getRoleCode())) {
            Role role = roleRepository.findByRoleCodeAndDeletedAtIsNull(userDTO.getRoleCode().trim())
                    .orElseThrow(() -> new CustomException("Invalid role code: " + userDTO.getRoleCode(), HttpStatus.UNPROCESSABLE_ENTITY));
            targetRoleIds.add(role.getId());
        } else if (userDTO.getId() == null) {
            Optional<Role> operatorRole = roleRepository.findByRoleCodeAndDeletedAtIsNull("OPERATOR");
            operatorRole.ifPresent(role -> targetRoleIds.add(role.getId()));
        }

        return targetRoleIds.stream().distinct().collect(Collectors.toList());
    }

    private UserDTO buildCreatedOrUpdatedUserDTO(User user, List<Long> targetRoleIds, Set<Long> targetDepartmentIds) {
        UserDTO responseUserDTO = mapUserToDTO(user);
        responseUserDTO.setId(user.getId());
        responseUserDTO.setRoleIds(new ArrayList<>(targetRoleIds));
        if (targetRoleIds.size() == 1) {
            responseUserDTO.setRoleId(targetRoleIds.get(0));
        }
        responseUserDTO.setDepartmentIds(new ArrayList<>(targetDepartmentIds));
        responseUserDTO.setSectionIds(new ArrayList<>(targetDepartmentIds));
        if (targetDepartmentIds.size() == 1) {
            Long assignedDepartmentId = targetDepartmentIds.iterator().next();
            responseUserDTO.setDepartmentId(assignedDepartmentId);
        }
        return responseUserDTO;
    }

    @Override
    public ResponseDTO<?> deleteUser(UserDTO userDTO) throws CustomException {
        try {
            if(Objects.equals(userDTO, null) || Objects.equals(userDTO.getId(), null)) {
                throw new CustomException("Please provide id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<User> userById = userRepository.findById(userDTO.getId());
            if(!userById.isPresent()) {
                throw new CustomException("Invalid id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String username = authentication.getName();
            Optional<User> loginUser = userRepository.findByUsernameIgnoreCase(username);
            List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(loginUser.get().getId());
            if(Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
                throw new CustomException("Invalid role", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Optional<UserRoleDepartment> userRoleDepartment =
                    userRoleDepartmentRepository.findByUser_IdAndRole_IdAndDepartment_IdAndDeletedByIsNull(userDTO.getId(), userDTO.getRoleId(), userDTO.getDepartmentId());
            if(userRoleDepartment.isPresent()) {
//                userRoleDepartment.get().setDeletedBy(loginUser.get());
//                userRoleDepartment.get().setDeletedAt(new Date());
                userRoleDepartmentRepository.delete(userRoleDepartment.get());
            } else {
                throw new CustomException("Invalid id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            return new ResponseDTO<>("Deleted user successfully", null);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getOperator(UserDTO userDTO) throws CustomException {
        try {
            if(Objects.equals(userDTO, null) || Objects.equals(userDTO.getId(), null)) {
                throw new CustomException("Please provide id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<User> userById = userRepository.findById(userDTO.getId());
            if(!userById.isPresent()) {
                throw new CustomException("Please provide valid id", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            UserDTO user = userDAO.getUserById(userDTO);
            return new ResponseDTO<>("User data fetched successfully", user);
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong ", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getAllOperators(UserDTO userDTO) throws CustomException {
        try {
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            Optional<Role> loginUserRole = roleRepository.findByRoleCode("SUBDEPT_ADMIN");
//            Optional<UserRoleDepartment> userRoleByUserIdDepartment =
//                    userRoleDepartmentRepository.findByUser_IdAndRole_Id(currentUser.get().getId(), loginUserRole.get().getId());
            List<UserRoleDepartment> userRoleDepartmentByUserIdAndRoleId =
                    userRoleDepartmentRepository.findByUserIdAndRoleId(currentUser.get().getId(), loginUserRole.get().getId());
//            if(Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
            if(userRoleDepartmentByUserIdAndRoleId.isEmpty()) {
                // Fallback removed - CHK_SHT_PREPARE role no longer exists
                throw new CustomException("Invalid role", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<Role> operatorUserRole = roleRepository.findByRoleCode("OPERATOR");
            userDTO.setRoleId(operatorUserRole.get().getId());
//            userDTO.setDepartmentId(userRoleByUserIdDepartment.get().getDepartmentId().getId());
            List<Long> subDepartmentIds = userRoleDepartmentByUserIdAndRoleId.stream().map(urd -> urd.getDepartmentId().getId()).collect(Collectors.toList());
//            userDTO.setDepartmentId(userRoleByUserIdDepartment.get().getDepartmentId().getId());
            userDTO.setDepartmentIds(subDepartmentIds);
            List<UserDTO> user = userDAO.getAllOperators(userDTO);
            return new ResponseDTO<>("User data fetched successfully", user);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong " + e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> getChecksheetPreparers(UserDTO userDTO) throws CustomException {
        return getUsersForDropdown(userDTO, UserDropdownPermissionGroups.CHECKSHEET_PREPARER, "Checksheet preparers fetched successfully");
    }

    @Override
    public ResponseDTO<?> getChecksheetValidators(UserDTO userDTO) throws CustomException {
        return getUsersForDropdown(userDTO, UserDropdownPermissionGroups.CHECKSHEET_VALIDATOR, "Checksheet validators fetched successfully");
    }

    @Override
    public ResponseDTO<?> getChecksheetApprovers(UserDTO userDTO) throws CustomException {
        return getUsersForDropdown(userDTO, UserDropdownPermissionGroups.CHECKSHEET_APPROVER, "Checksheet approvers fetched successfully");
    }

    @Override
    public ResponseDTO<?> getDataValidators(UserDTO userDTO) throws CustomException {
        return getUsersForDropdown(userDTO, UserDropdownPermissionGroups.DATA_VALIDATOR, "Data validators fetched successfully");
    }

    @Override
    public ResponseDTO<?> getDataApprovers(UserDTO userDTO) throws CustomException {
        return getUsersForDropdown(userDTO, UserDropdownPermissionGroups.DATA_APPROVER, "Data approvers fetched successfully");
    }

    @Override
    public ResponseDTO<?> getChecksheetOperators(UserDTO userDTO) throws CustomException {
        return getUsersForDropdown(userDTO, UserDropdownPermissionGroups.OPERATOR, "Operators fetched successfully");
    }

    @Override
    public ResponseDTO<?> getAlertUsers(UserDTO userDTO) throws CustomException {
        return getUsersForDropdown(userDTO, null, "Alert users fetched successfully");
    }

    @Override
    public ResponseDTO<?> getEscalationUsers(UserDTO userDTO) throws CustomException {
        return getUsersForDropdown(userDTO, null, "Escalation users fetched successfully");
    }

    private ResponseDTO<?> getUsersForDropdown(UserDTO userDTO, List<String> permissionCodes, String successMessage) throws CustomException {
        try {
            if (userDTO == null) {
                userDTO = new UserDTO();
            }

            boolean hasDepartmentFilter = userDTO.getDepartmentId() != null
                    || (userDTO.getDepartmentIds() != null && !userDTO.getDepartmentIds().isEmpty());
            boolean hasSectionFilter = userDTO.getSectionIds() != null && !userDTO.getSectionIds().isEmpty();

            if (!hasDepartmentFilter && !hasSectionFilter) {
                throw new CustomException("Please provide department or section", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            if (!currentUser.isPresent()) {
                throw new CustomException("Logged in user not found", HttpStatus.UNAUTHORIZED);
            }

            List<Long> allowedDepartmentIds = permissionService.getAllowedDepartmentIds(currentUser.get().getId());
            int pageNumber = userDTO.getPage() != null && userDTO.getPage() >= 0 ? userDTO.getPage() : 0;
            Pageable pageable = PageRequest.of(pageNumber, 10);

            if (permissionCodes != null && !permissionCodes.isEmpty()) {
                userDTO.setPermissionCodes(permissionCodes);
            }

            Page<UserDTO> page = userDAO.getUsersByPermission(userDTO, permissionCodes, pageable, allowedDepartmentIds);

            return new ResponseDTO<>(
                    successMessage,
                    page.getContent(),
                    page.getTotalElements(),
                    page.getTotalPages(),
                    page.getNumber(),
                    page.getSize()
            );
        } catch (CustomException ce) {
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong while fetching users", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    @Override
    public ResponseDTO<?> login(UserDTO userDTO) throws CustomException {
        try {
            try {
                boolean isLdapRequired = StringUtils.hasText(ldapEnvironment) && !Objects.isNull(userDTO.getDeviceType()) &&
                        "WEB".equalsIgnoreCase(userDTO.getDeviceType());

                if (isLdapRequired) {
                    return handleLdapLogin(userDTO);
                } else {
                    return handleRegularLogin(userDTO);
                }
            } catch (CustomException ce) {
                throw ce;
            } catch (Exception e) {
                e.printStackTrace();
                throw new CustomException("Invalid username or password", HttpStatus.UNAUTHORIZED);
            }
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Invalid username or password", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private ResponseDTO<?> handleLdapLogin(UserDTO userDTO) throws Exception {
        Optional<User> user = userRepository.findByUsernameIgnoreCase(userDTO.getUsername());
        if (!user.isPresent()) {
            throw new CustomException("User does not exist", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        List<String> allRoles = userRoleDepartmentDAO.getUserByEmailAndUserId(user.get().getId());
        if(Objects.isNull(allRoles) || allRoles.isEmpty()) {
            throw new CustomException("User does not exist", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        // Check if user has operator permissions (can fill checksheets)
        boolean hasOperatorPermission = permissionService.hasPermission(user.get().getId(), "CHECKSHEET_FILL_ANSWER");
        if(!hasOperatorPermission) {
            UserDetails ldapUser = LdapAuth.checkWithLDAP(userDTO.getUsername(), userDTO.getPassword());

            user.get().setFirstName(ldapUser.getFirstName());
            System.out.println("First Name : " +ldapUser.getFirstName());
            user.get().setLastName(ldapUser.getLastName());
            System.out.println("Last Name : " + ldapUser.getLastName());
            user.get().setEmail(ldapUser.getMailAddress());
            if (!Constants.SUCCESS.equals(ldapUser.getAuthStatus())) {
                user.get().setStatus("I");
                throw new CustomException("Invalid username or password", HttpStatus.UNAUTHORIZED);
            }
            user.get().setStatus("A");
        }
        return setUserResponseDTO(user.get(),allRoles,userDTO.getDeviceType());

    }

    private ResponseDTO<?> handleRegularLogin(UserDTO userDTO) throws CustomException, ParseException {
        try {
            Optional<User> user = userRepository.findByUsernameIgnoreCase(userDTO.getUsername());
            if (user.isEmpty()) {
                throw new CustomException("Invalid username or password", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if (Objects.isNull(userDTO.getDeviceType()) || (!Objects.equals(userDTO.getDeviceType(), "APP") && !Objects.equals(userDTO.getDeviceType(), "WEB"))) {
                throw new CustomException("Please provide valid deviceType", HttpStatus.UNAUTHORIZED);
            }
            Long loginCount = ((Objects.equals(user.get().getFailLoginCount(), null) ||
                (user.get().getFailLoginCount() >= totalLoginCount && !Objects.equals(user.get().getResendOtpTime(), null)
                    && user.get().getResendOtpTime().before(new Date()))) ? 0L : user.get().getFailLoginCount());
            loginCount += 1L;
            if (loginCount > totalLoginCount && !Objects.equals(user.get().getResendOtpTime(), null) && user.get().getResendOtpTime().after(new Date())) {
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
                Date resendOtpDate = dateFormat.parse(user.get().getResendOtpTime().toString());
                Date currentDate = new Date();

                // Convert Date to Instant
                Instant resendOtpInstant = resendOtpDate.toInstant();
                Instant currentInstant = currentDate.toInstant();

                // Calculate the duration between the two instants
                Duration duration = Duration.between(currentInstant, resendOtpInstant);

                // Get the difference in minutes
                long minutesDifference = duration.toMinutes() + 1;
                throw new CustomException("You can relogin after " + minutesDifference + (minutesDifference > 1 ? " minutes" : " minute"), HttpStatus.UNPROCESSABLE_ENTITY);
            }
            user.get().setFailLoginCount(Math.toIntExact(loginCount));
            if (loginCount >= totalLoginCount) {
                user.get().setResendOtpTime(Date.from(Instant.now().plusSeconds(resendOTPTime * 60)));
            }
            userRepository.save(user.get());
            authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                    userDTO.getUsername(),
                    userDTO.getPassword()
                )
            );

            List<String> allRoles = userRoleDepartmentDAO.getUserByEmailAndUserId(user.get().getId());
            return setUserResponseDTO(user.get(), allRoles, userDTO.getDeviceType());
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Invalid username or password",HttpStatus.BAD_REQUEST);
        }
    }

    private ResponseDTO<?> setUserResponseDTO(User user, List<String> allRoles, String deviceType) throws CustomException {
        // Get effective permissions for user from PermissionService (includes hierarchy)
        Set<String> allPermissions = new HashSet<>();
        try {
            allPermissions = permissionService.getEffectivePermissionsForUser(user.getId());
        } catch (Exception e) {
            e.printStackTrace();
            // Re-throw the exception instead of silently failing
            throw new CustomException("Error fetching permissions: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
        
        // Validate user has at least some permissions - prevent login if no permissions
        if (allPermissions.isEmpty()) {
            throw new CustomException("You have no permissions assigned. Please contact your administrator.", HttpStatus.FORBIDDEN);
        }

        // Generate JWT token with permissions included
        Map<String, Object> extraClaims = new HashMap<>();
        extraClaims.put("permissions", new ArrayList<>(allPermissions));
        extraClaims.put("roles", allRoles);
        var jwtToken = jwtService.generateToken(extraClaims, user);
        
        user.setJwt_token(jwtToken);
        user.setFailLoginCount(0);
        user.setResendOtpTime(null);
        userRepository.save(user);

        UserDTO responseUserDTO = new UserDTO();
        responseUserDTO.setAccessToken(jwtToken);
        responseUserDTO.setFirstName(user.getFirstName());
        responseUserDTO.setLastName(user.getLastName());
        responseUserDTO.setEmail(user.getEmail());
        responseUserDTO.setStatus(user.getStatus());
        responseUserDTO.setUsername(user.getUsername());
        responseUserDTO.setMobile(user.getMobile());
        responseUserDTO.setAllRoles(allRoles);
        responseUserDTO.setRoleNames(userRoleDepartmentDAO.getRoleNamesForUser(user.getId()));
        responseUserDTO.setPermissions(new ArrayList<>(allPermissions));

        // Permission-based menu assignment
        // SUPER_ADMIN gets all menus, others get menus based on permissions
        Set<String> allMenus = new HashSet<>();
        boolean isSuperAdmin = allRoles.contains(SystemRole.SUPER_ADMIN);
        
        if (isSuperAdmin) {
            // SUPER_ADMIN gets all menus
            allMenus.addAll(getSuperAdminMenus().stream().toList());
        } else {
            // For non-SUPER_ADMIN users, derive menus from permissions
            allMenus.addAll(getMenusFromPermissions(allPermissions));
        }
        responseUserDTO.setMenus(allMenus.stream().toList());
        String refreshToken = refreshTokenService.generateRefreshToken(user.getUsername(), true, deviceType);
        responseUserDTO.setRefreshToken(refreshToken);
        return new ResponseDTO<>("You have successfully logged in!!!", responseUserDTO);
    }

    @Override
    @Transactional
    public ResponseDTO<?> validateAndSaveUsername(UserDTO userDTO) throws CustomException {
        try {
            if (!StringUtils.hasText(userDTO.getUsername())) {
                throw new CustomException("Username is required", HttpStatus.BAD_REQUEST);
            }

            boolean isLdapRequired = StringUtils.hasText(ldapEnvironment);

            if (isLdapRequired) {
                return handleLdapValidation(userDTO.getUsername());
            } else {
                return handleLocalValidation(userDTO.getUsername());
            }
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Failed to validate Username: " + e.getMessage(),
                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    protected ResponseDTO<?> handleLdapValidation(String username) throws Exception {
        String dn = LdapAuth.getUid(username);
        UserDetails usrLdap = null;
        Optional<User> existingUser = userRepository.findByUsernameIgnoreCase(username);
        User user = null;

        if (dn != null) {
            usrLdap = LdapAuth.getUserBasicAttributes(username);
            if (usrLdap == null || Objects.isNull(usrLdap.getFirstName()) || usrLdap.getFirstName().isEmpty()
                    || Objects.isNull(usrLdap.getLastName()) || usrLdap.getLastName().isEmpty()
                    || Objects.isNull(usrLdap.getMailAddress()) || usrLdap.getMailAddress().isEmpty()) {

                if (existingUser.isPresent()) {
                    System.out.println("existingUser: " + existingUser);
                    user = existingUser.get();
                    user.setStatus("I");
                    userRepository.save(user);
                }
                return new ResponseDTO<>("Invalid LDAP user details", HttpStatus.UNPROCESSABLE_ENTITY);
            }
        } else {
            if (existingUser.isPresent()) {
                user = existingUser.get();
                user.setStatus("I");
                userRepository.save(user);
            }
            System.out.println("dn: " + dn);
            return new ResponseDTO<>("User not found in LDAP (" + username + ")", HttpStatus.UNPROCESSABLE_ENTITY);
        }

        System.out.println("existingUser.isPresent(): " + existingUser.isPresent());
        if (existingUser.isPresent()) {
            user = existingUser.get();
        } else {
            user = new User();
            user.setUsername(username);
        }
        user.setFirstName(usrLdap.getFirstName());
        user.setLastName(usrLdap.getLastName());
        user.setEmail(usrLdap.getMailAddress());
        user.setStatus("A");
        System.out.println("user: " + user);
        user = userRepository.save(user);
        return new ResponseDTO<>(true, "Username validated successfully", mapUserToDTO(user));
    }

    private ResponseDTO<?> handleLocalValidation(String username) throws CustomException {
        try{
            Optional<User> existingUser = userRepository.findByUsernameIgnoreCase(username);

            if (!existingUser.isPresent()) {
    //            return new ResponseDTO<>("User not found in LDAP", HttpStatus.UNPROCESSABLE_ENTITY);
                User newUser = createNewUserFromUsername(username);
                newUser.setStatus("A");
                newUser = userRepository.save(newUser);
                return new ResponseDTO<>("Username validated and saved successfully",
                        mapUserToDTO(newUser));
            }

            return new ResponseDTO<>("Username already exists",
                    mapUserToDTO(existingUser.get()));
        }catch (Exception e){
            throw new CustomException("Something went wrong...", HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }

    private UserDTO mapUserToDTO(User user) {
        return UserDTO.builder()
//                .id(user.getId())
                .username(user.getUsername())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .status(user.getStatus())
                .build();
    }


    private User createNewUserFromUsername(String username) {
        String cleanUsername = username.trim().replaceAll("\\s+", "").toUpperCase();
        User user = new User();
        user.setUsername(cleanUsername);
        user.setFirstName(cleanUsername);
        user.setLastName(cleanUsername);
        user.setEmail(cleanUsername.toLowerCase() + "@gmail.com");
        user.setPassword(passwordEncoder.encode("12345678"));
        return user;
    }

    @Override
    public synchronized UserDTO refreshToken(UserDTO userDTO) throws CustomException {
        try {
            if (Objects.isNull(userDTO) || Objects.isNull(userDTO.getRefreshToken()) || Objects.isNull(userDTO.getDeviceType())) {
                throw new CustomException("Please provide deviceType, refreshToken", HttpStatus.UNAUTHORIZED);
            }
            String requestRefreshToken = userDTO.getRefreshToken();
            String deviceType = userDTO.getDeviceType();
            Optional<RefreshToken> refreshToken = refreshTokenRepository.findByTokenAndDeviceType(requestRefreshToken, deviceType);
            if (!refreshToken.isPresent()) {
                throw new CustomException("Session expired!", HttpStatus.UNAUTHORIZED);
            }
            if (refreshToken.get().getExpiryDate().compareTo(new Date().toInstant()) < 0) {
                throw new CustomException("Session expired!", HttpStatus.UNAUTHORIZED);
            }
            Optional<User> user = userRepository.findById(refreshToken.get().getUser().getId());
            var jwtToken = jwtService.generateToken(user.get());
            if (Objects.equals(deviceType, "APP")) {
                user.get().setJwt_token(jwtToken);
                userRepository.save(user.get());
            }
            if (!Objects.equals(deviceType, "APP") && !Objects.equals(deviceType, "WEB")) {
                throw new CustomException("Please provide valid deviceType", HttpStatus.UNAUTHORIZED);
            }
            return refreshToken
                    .map(RefreshToken::getUser)
                    .map(user1 -> {
                        String tempRefreshToken = null;
                        try {
                            tempRefreshToken = refreshTokenService.generateRefreshToken(user1.getUsername(), false, deviceType);
                        } catch (CustomException e) {
                            throw new RuntimeException(e);
                        }
                        UserDTO responseUserDTO = new UserDTO();
                        responseUserDTO.setRefreshToken(tempRefreshToken);
                        responseUserDTO.setAccessToken(jwtToken);
                        return responseUserDTO;
                    })
                    .orElseThrow(() -> new CustomException("Session expired!", new BadRequestException("Session expired!")));
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Something went wrong " + e.getMessage(), HttpStatus.UNAUTHORIZED);
        }
    }

    public List<String> getSuperAdminPermissions() {
        if(superAdminPermissions.isEmpty()) {
            superAdminPermissions.add("DEPARTMENT_CREATE");
            superAdminPermissions.add("DEPARTMENT_EDIT");
            superAdminPermissions.add("DEPARTMENT_LIST");
            superAdminPermissions.add("DEPARTMENT_DOWNLOAD");
            superAdminPermissions.add("SUBDEPARTMENT_LIST");
            superAdminPermissions.add("SUBDEPARTMENT_DOWNLOAD");
            superAdminPermissions.add("USER_LIST");
            superAdminPermissions.add("USER_CREATE");
            superAdminPermissions.add("USER_EDIT");
            superAdminPermissions.add("USER_PASSWORD_RESET");
            superAdminPermissions.add("USER_DELETE");
            superAdminPermissions.add("USER_DOWNLOAD");
//            superAdminPermissions.add("CHECKSHEET_MANAGEMENT_LIST");
//            superAdminPermissions.add("CHECKSHEET_MANAGEMENT_DETAIL_VIEW");
//            superAdminPermissions.add("CHECKSHEET_MANAGEMENT_TEMPLATE_VIEW");
//            superAdminPermissions.add("CHECKSHEET_MANAGEMENT_CONTENT_VIEW");
//            superAdminPermissions.add("CHECKSHEET_MANAGEMENT_VALIDATOR_COMMENT_VIEW");
//            superAdminPermissions.add("CHECKSHEET_MANAGEMENT_APPROVER_COMMENT_VIEW");
//            superAdminPermissions.add("CHECKSHEET_LISTING");
//            superAdminPermissions.add("CHECKSHEET_DATA_VALIDATOR_COMMENT_VIEW");
//            superAdminPermissions.add("CHECKSHEET_DATA_APPROVER_COMMENT_VIEW");
        }
        return superAdminPermissions;
    }

    public List<String> getDepartmentAdminPermissions() {
        if(departmentAdminPermissions.isEmpty()) {
            departmentAdminPermissions.add("SUBDEPARTMENT_CREATE");
            departmentAdminPermissions.add("SUBDEPARTMENT_EDIT");
            departmentAdminPermissions.add("SUBDEPARTMENT_LIST");
            departmentAdminPermissions.add("SUBDEPARTMENT_DOWNLOAD");
			departmentAdminPermissions.add("USER_CREATE");
            departmentAdminPermissions.add("USER_EDIT");
            departmentAdminPermissions.add("USER_PASSWORD_RESET");
            departmentAdminPermissions.add("USER_DELETE");
            departmentAdminPermissions.add("USER_LIST");
            departmentAdminPermissions.add("USER_DOWNLOAD");
//            departmentAdminPermissions.add("CHECKSHEET_MANAGEMENT_LIST");
//            departmentAdminPermissions.add("CHECKSHEET_MANAGEMENT_DETAIL_VIEW");
//            departmentAdminPermissions.add("CHECKSHEET_MANAGEMENT_TEMPLATE_VIEW");
//            departmentAdminPermissions.add("CHECKSHEET_MANAGEMENT_CONTENT_VIEW");
//            departmentAdminPermissions.add("CHECKSHEET_MANAGEMENT_VALIDATOR_COMMENT_VIEW");
//            departmentAdminPermissions.add("CHECKSHEET_MANAGEMENT_APPROVER_COMMENT_VIEW");
//            departmentAdminPermissions.add("CHECKSHEET_LISTING");
//            departmentAdminPermissions.add("CHECKSHEET_DATA_VALIDATOR_COMMENT_VIEW");
//            departmentAdminPermissions.add("CHECKSHEET_DATA_APPROVER_COMMENT_VIEW");
        }
        return departmentAdminPermissions;
    }

    public List<String> getChecksheetPreparerPermissions() {
        if(checksheetPreparerPermissions.isEmpty()) {
            checksheetPreparerPermissions.add("CHECKSHEET_MANAGEMENT_LIST");
            checksheetPreparerPermissions.add("CHECKSHEET_MANAGEMENT_DETAIL_VIEW");
            checksheetPreparerPermissions.add("CHECKSHEET_MANAGEMENT_TEMPLATE_CREATE");
            checksheetPreparerPermissions.add("CHECKSHEET_MANAGEMENT_TEMPLATE_EDIT");
            checksheetPreparerPermissions.add("CHECKSHEET_MANAGEMENT_TEMPLATE_VIEW");
            checksheetPreparerPermissions.add("CHECKSHEET_MANAGEMENT_CONTENT_VIEW");
            checksheetPreparerPermissions.add("CHECKSHEET_MANAGEMENT_VALIDATOR_COMMENT_VIEW");
            checksheetPreparerPermissions.add("CHECKSHEET_MANAGEMENT_APPROVER_COMMENT_VIEW");
            checksheetPreparerPermissions.add("CHECKSHEET_LISTING");
            checksheetPreparerPermissions.add("CHECKSHEET_DATA_VALIDATOR_COMMENT_VIEW");
            checksheetPreparerPermissions.add("CHECKSHEET_DATA_APPROVER_COMMENT_VIEW");
        }
        return checksheetPreparerPermissions;
    }

    public List<String> getChecksheetValidatorPermissions() {
        if(checksheetValidatorPermissions.isEmpty()) {
            checksheetValidatorPermissions.add("CHECKSHEET_MANAGEMENT_LIST");
            checksheetValidatorPermissions.add("CHECKSHEET_MANAGEMENT_DETAIL_VIEW");
            checksheetValidatorPermissions.add("CHECKSHEET_MANAGEMENT_TEMPLATE_VIEW");
            checksheetValidatorPermissions.add("CHECKSHEET_MANAGEMENT_CONTENT_VIEW");
            checksheetValidatorPermissions.add("CHECKSHEET_MANAGEMENT_VALIDATOR_COMMENT_CREATE");
            checksheetValidatorPermissions.add("CHECKSHEET_MANAGEMENT_VALIDATOR_COMMENT_EDIT");
            checksheetValidatorPermissions.add("CHECKSHEET_MANAGEMENT_VALIDATOR_COMMENT_VIEW");
            checksheetValidatorPermissions.add("CHECKSHEET_MANAGEMENT_APPROVER_COMMENT_VIEW");
            checksheetValidatorPermissions.add("CHECKSHEET_LISTING");
            checksheetValidatorPermissions.add("CHECKSHEET_DATA_VALIDATOR_COMMENT_VIEW");
            checksheetValidatorPermissions.add("CHECKSHEET_DATA_APPROVER_COMMENT_VIEW");
        }
        return checksheetValidatorPermissions;
    }

    public List<String> getChecksheetApproverPermissions() {
        if(checksheetApproverPermissions.isEmpty()) {
            checksheetApproverPermissions.add("CHECKSHEET_MANAGEMENT_LIST");
            checksheetApproverPermissions.add("CHECKSHEET_MANAGEMENT_DETAIL_VIEW");
            checksheetApproverPermissions.add("CHECKSHEET_MANAGEMENT_TEMPLATE_VIEW");
            checksheetApproverPermissions.add("CHECKSHEET_MANAGEMENT_CONTENT_VIEW");
            checksheetApproverPermissions.add("CHECKSHEET_MANAGEMENT_VALIDATOR_COMMENT_VIEW");
            checksheetApproverPermissions.add("CHECKSHEET_MANAGEMENT_APPROVER_COMMENT_VIEW");
            checksheetApproverPermissions.add("CHECKSHEET_MANAGEMENT_APPROVER_COMMENT_CREATE");
            checksheetApproverPermissions.add("CHECKSHEET_MANAGEMENT_APPROVER_COMMENT_EDIT");
            checksheetApproverPermissions.add("CHECKSHEET_LISTING");
            checksheetApproverPermissions.add("CHECKSHEET_DATA_VALIDATOR_COMMENT_VIEW");
            checksheetApproverPermissions.add("CHECKSHEET_DATA_APPROVER_COMMENT_VIEW");
        }
        return checksheetApproverPermissions;
    }

    public List<String> getChecksheetDataValidatorPermissions() {
        if(checksheetDataValidatorPermissions.isEmpty()) {
            checksheetDataValidatorPermissions.add("CHECKSHEET_LISTING");
            checksheetDataValidatorPermissions.add("CHECKSHEET_DATA_VALIDATOR_COMMENT_CREATE");
            checksheetDataValidatorPermissions.add("CHECKSHEET_DATA_VALIDATOR_COMMENT_EDIT");
            checksheetDataValidatorPermissions.add("CHECKSHEET_DATA_VALIDATOR_COMMENT_VIEW");
            checksheetDataValidatorPermissions.add("CHECKSHEET_DATA_APPROVER_COMMENT_VIEW");
            checksheetDataValidatorPermissions.add("NPD_CREATE");
        }
        return checksheetDataValidatorPermissions;
    }

    public List<String> getChecksheetDataApproverPermissions() {
        if(checksheetDataApproverPermissions.isEmpty()) {
            checksheetDataApproverPermissions.add("CHECKSHEET_LISTING");
            checksheetDataApproverPermissions.add("CHECKSHEET_DATA_VALIDATOR_COMMENT_VIEW");
            checksheetDataApproverPermissions.add("CHECKSHEET_DATA_APPROVER_COMMENT_CREATE");
            checksheetDataApproverPermissions.add("CHECKSHEET_DATA_APPROVER_COMMENT_EDIT");
            checksheetDataApproverPermissions.add("CHECKSHEET_DATA_APPROVER_COMMENT_VIEW");
        }
        return checksheetDataApproverPermissions;
    }

    public List<String> getOperatorPermissions() {
        if(operatorPermissions.isEmpty()) {
            operatorPermissions.add("CHECKSHEET_FILL_LISTING");
            operatorPermissions.add("CHECKSHEET_FILL_CHECKSHEET_DETAIL");
            operatorPermissions.add("CHECKSHEET_FILL_ANSWER");
        }
        return operatorPermissions;
    }

    public List<String> getSubDepartmentAdminPermissions() {
        if(subDepartmentAdminPermissions.isEmpty()) {
            subDepartmentAdminPermissions.add("USER_CREATE");
            subDepartmentAdminPermissions.add("USER_EDIT");
            subDepartmentAdminPermissions.add("USER_PASSWORD_RESET");
            subDepartmentAdminPermissions.add("USER_DELETE");
            subDepartmentAdminPermissions.add("USER_LIST");
            subDepartmentAdminPermissions.add("USER_DOWNLOAD");
            subDepartmentAdminPermissions.add("CHECKSHEET_MANAGEMENT_LIST");
            subDepartmentAdminPermissions.add("CHECKSHEET_MANAGEMENT_DETAIL_CREATE");
            subDepartmentAdminPermissions.add("CHECKSHEET_MANAGEMENT_DETAIL_EDIT");
            subDepartmentAdminPermissions.add("CHECKSHEET_MANAGEMENT_DETAIL_VIEW");
            subDepartmentAdminPermissions.add("CHECKSHEET_MANAGEMENT_TEMPLATE_VIEW");
            subDepartmentAdminPermissions.add("CHECKSHEET_MANAGEMENT_CONTENT_VIEW");
            subDepartmentAdminPermissions.add("CHECKSHEET_MANAGEMENT_VALIDATOR_COMMENT_VIEW");
            subDepartmentAdminPermissions.add("CHECKSHEET_MANAGEMENT_APPROVER_COMMENT_VIEW");
            subDepartmentAdminPermissions.add("CHECKSHEET_LISTING");
            subDepartmentAdminPermissions.add("CHECKSHEET_DATA_VALIDATOR_COMMENT_VIEW");
            subDepartmentAdminPermissions.add("CHECKSHEET_DATA_APPROVER_COMMENT_VIEW");
        }
        return subDepartmentAdminPermissions;
    }

    /**
     * Permission to Menu mapping.
     * Maps permissions to the menus they grant access to.
     * This replaces the old role-based menu assignment.
     */
    private static final Map<String, List<String>> PERMISSION_MENU_MAP = new LinkedHashMap<>();
    static {
        // Dashboard access - available to all authenticated users
        PERMISSION_MENU_MAP.put("DASHBOARD_VIEW", List.of(
            "main-dashboard",       // BI: Dealer Network Intelligence (national/region/dealer/location drill)
            "dashboard",            // Legacy checksheet-summary funnel + heatmap (pre-V1.24)
            "plan-vs-actual", "checksheet-summary", "trend-analysis"));
        
        // Department management
        PERMISSION_MENU_MAP.put("DEPARTMENT_LIST", List.of("department"));
        PERMISSION_MENU_MAP.put("DEPARTMENT_CREATE", List.of("department/create"));
        PERMISSION_MENU_MAP.put("DEPARTMENT_EDIT", List.of("department/edit"));
        
        // Section/Subdepartment management
        PERMISSION_MENU_MAP.put("SUBDEPARTMENT_LIST", List.of("section"));
        PERMISSION_MENU_MAP.put("SUBDEPARTMENT_CREATE", List.of("section/create"));
        PERMISSION_MENU_MAP.put("SUBDEPARTMENT_EDIT", List.of("section/edit"));
        
        // User management
        PERMISSION_MENU_MAP.put("USER_LIST", List.of("user"));
        PERMISSION_MENU_MAP.put("USER_CREATE", List.of("user/create"));
        PERMISSION_MENU_MAP.put("USER_EDIT", List.of("user/edit"));
        
        // Checksheet management
        PERMISSION_MENU_MAP.put("CHECKSHEET_MANAGEMENT_LIST", List.of("checksheet-management"));
        PERMISSION_MENU_MAP.put("CHKS_ASSIGNMENT_TO_AUDITEE", List.of("checksheet-management/assign-auditee"));
        PERMISSION_MENU_MAP.put("CHECKSHEET_MANAGEMENT_DETAIL_CREATE", List.of("checksheet-management/create"));
        PERMISSION_MENU_MAP.put("CHECKSHEET_MANAGEMENT_DETAIL_EDIT", List.of("checksheet-management/edit", "checksheet-management/revise"));
        
        // Checksheet fill (operators)
        PERMISSION_MENU_MAP.put("CHECKSHEET_FILL_LISTING", List.of("checksheets"));
        PERMISSION_MENU_MAP.put("CHECKSHEET_FILL_ANSWER", List.of("checksheets"));
        PERMISSION_MENU_MAP.put("CHECKSHEET_LISTING", List.of("checksheets"));
        
        // Non-production day
        PERMISSION_MENU_MAP.put("NPD_LIST", List.of("non-production-day"));
        PERMISSION_MENU_MAP.put("NPD_CREATE", List.of("non-production-day"));
    }
    
    /**
     * Derive menus from user's effective permissions.
     * This is the new permission-based menu assignment.
     */
    private Set<String> getMenusFromPermissions(Set<String> permissions) {
        Set<String> menus = new HashSet<>();
        
        // Always add dashboard for authenticated users
        menus.add("dashboard");
        menus.add("plan-vs-actual");
        menus.add("checksheet-summary");
        menus.add("trend-analysis");
        
        // Map permissions to menus
        for (String permission : permissions) {
            List<String> mappedMenus = PERMISSION_MENU_MAP.get(permission);
            if (mappedMenus != null) {
                menus.addAll(mappedMenus);
            }
        }
        
        return menus;
    }

    public List<String> getSuperAdminMenus() {
        if(superAdminMenus.isEmpty()) {
//            superAdminMenus.add("DEPARTMENT");
//            superAdminMenus.add("SECTION");
//            superAdminMenus.add("USER");
//            superAdminMenus.add("CHECKSHEET_MANAGEMENT");
//            superAdminMenus.add("CHECKSHEET");
            superAdminMenus.add("dashboard");
            superAdminMenus.add("main-dashboard");
            superAdminMenus.add("regional-dashboard");
            superAdminMenus.add("dealer-dashboard");
            superAdminMenus.add("plan-vs-actual");
            superAdminMenus.add("checksheet-summary");
            superAdminMenus.add("trend-analysis");
            superAdminMenus.add("department");
            superAdminMenus.add("department/create");
            superAdminMenus.add("department/edit");
            superAdminMenus.add("section");
            superAdminMenus.add("section/create");
            superAdminMenus.add("section/edit");
            superAdminMenus.add("user");
            superAdminMenus.add("user/create");
            superAdminMenus.add("user/edit");

            // Role management (SUPER_ADMIN only)
            superAdminMenus.add("role");
            superAdminMenus.add("role/create");
            superAdminMenus.add("role/edit");

            // Permission management (SUPER_ADMIN only)
            superAdminMenus.add("permission");
            superAdminMenus.add("permission/create");
            superAdminMenus.add("permission/edit");

            // Master Department management (SUPER_ADMIN only)
            superAdminMenus.add("master-department");
            superAdminMenus.add("master-department/create");
            superAdminMenus.add("master-department/edit");

        }
        return superAdminMenus;
    }

    public List<String> getDepartmentAdminMenus() {
        if(departmentAdminMenus.isEmpty()) {
//            departmentAdminMenus.add("SECTION");
//            departmentAdminMenus.add("USER");
//            departmentAdminMenus.add("CHECKSHEET_MANAGEMENT");
//            departmentAdminMenus.add("CHECKSHEET");
            departmentAdminMenus.add("dashboard");
            departmentAdminMenus.add("plan-vs-actual");
            departmentAdminMenus.add("checksheet-summary");
            departmentAdminMenus.add("trend-analysis");
            departmentAdminMenus.add("section");
            departmentAdminMenus.add("section/create");
            departmentAdminMenus.add("section/edit");
            departmentAdminMenus.add("user");
            departmentAdminMenus.add("user/create");
            departmentAdminMenus.add("user/edit");
        }
        return departmentAdminMenus;
    }

    public List<String> getSubDepartmentAdminMenus() {
        if(subDepartmentAdminMenus.isEmpty()) {
//            subDepartmentAdminMenus.add("USER");
//            subDepartmentAdminMenus.add("CHECKSHEET_MANAGEMENT");
//            subDepartmentAdminMenus.add("CHECKSHEET");
            subDepartmentAdminMenus.add("dashboard");
            subDepartmentAdminMenus.add("plan-vs-actual");
            subDepartmentAdminMenus.add("checksheet-summary");
            subDepartmentAdminMenus.add("trend-analysis");
            subDepartmentAdminMenus.add("user");
            subDepartmentAdminMenus.add("user/create");
            subDepartmentAdminMenus.add("user/edit");
            subDepartmentAdminMenus.add("checksheet-management");
            subDepartmentAdminMenus.add("checksheet-management/create");
            subDepartmentAdminMenus.add("checksheet-management/edit");
            subDepartmentAdminMenus.add("checksheets");
        }
        return subDepartmentAdminMenus;
    }

    public List<String> getChecksheetPrepareMenus() {
        if(checksheetPrepareMenus.isEmpty()) {
//            checksheetPrepareMenus.add("CHECKSHEET_MANAGEMENT");
//            checksheetPrepareMenus.add("CHECKSHEET");
            checksheetPrepareMenus.add("dashboard");
            checksheetPrepareMenus.add("checksheet-management");
            checksheetPrepareMenus.add("checksheet-management/revise");
            checksheetPrepareMenus.add("checksheets");
            checksheetPrepareMenus.add("plan-vs-actual");
            checksheetPrepareMenus.add("checksheet-summary");
            checksheetPrepareMenus.add("trend-analysis");
        }
        return checksheetPrepareMenus;
    }
    public List<String> getChecksheetValidatorAndApprovalMenus() {
        if(checksheetValidationAndApprovalMenus.isEmpty()) {
            checksheetValidationAndApprovalMenus.add("dashboard");
            checksheetValidationAndApprovalMenus.add("checksheet-management");
            checksheetValidationAndApprovalMenus.add("checksheets");
            checksheetValidationAndApprovalMenus.add("plan-vs-actual");
            checksheetValidationAndApprovalMenus.add("checksheet-summary");
            checksheetValidationAndApprovalMenus.add("trend-analysis");
        }
        return checksheetValidationAndApprovalMenus;
    }

    public List<String> getChecksheetDataValidationMenus() {
        if(checksheetDataValidationMenus.isEmpty()) {
            checksheetDataValidationMenus.add("dashboard");
            checksheetDataValidationMenus.add("checksheets");
            checksheetDataValidationMenus.add("plan-vs-actual");
            checksheetDataValidationMenus.add("checksheet-summary");
            checksheetDataValidationMenus.add("trend-analysis");
            checksheetDataValidationMenus.add("non-production-day");
        }
        return checksheetDataValidationMenus;
    }

    public List<String> getChecksheetDataApprovalMenus() {
        if(checksheetDataApprovalMenus.isEmpty()) {
            checksheetDataApprovalMenus.add("dashboard");
            checksheetDataApprovalMenus.add("checksheets");
            checksheetDataApprovalMenus.add("plan-vs-actual");
            checksheetDataApprovalMenus.add("checksheet-summary");
            checksheetDataApprovalMenus.add("trend-analysis");
        }
        return checksheetDataApprovalMenus;
    }

    @Override
    public ResponseDTO<?> updatePassword(UserDTO userDTO) throws CustomException {
        try {
            if (Objects.isNull(userDTO.getPassword()) || Objects.isNull(userDTO.getConfirmPassword())) {
                throw new CustomException("Password and confirm password are required", HttpStatus.BAD_REQUEST);
            }

            if (!userDTO.getPassword().equals(userDTO.getConfirmPassword())) {
                throw new CustomException("Password and confirm password do not match", HttpStatus.BAD_REQUEST);
            }

            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            if (!currentUser.isPresent()) {
                throw new CustomException("User not found", HttpStatus.NOT_FOUND);
            }

            // Check if user has CHECKSHEET_FILL_ANSWER permission (operator permission)
            if (!permissionService.hasPermission(currentUser.get().getId(), "CHECKSHEET_FILL_ANSWER")) {
                throw new CustomException("Unauthorized access", HttpStatus.FORBIDDEN);
            }

            User user = currentUser.get();
            user.setPassword(passwordEncoder.encode(userDTO.getPassword()));
            userRepository.save(user);

            return new ResponseDTO<>(true, "Password reset successfully");
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Failed to reset password: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseDTO<?> resetPassword(UserDTO userDTO) throws CustomException {
        try {
            if (Objects.isNull(userDTO.getId())) {
                throw new CustomException("Id required", HttpStatus.BAD_REQUEST);
            }
            Optional<User> currentUser = utilityService.getCurrentLoggedInUser();
            if (!currentUser.isPresent()) {
                throw new CustomException("User not found", HttpStatus.NOT_FOUND);
            }

            // Check if user has USER_PASSWORD_RESET permission
            if (!permissionService.hasPermission(currentUser.get().getId(), "USER_PASSWORD_RESET")) {
                throw new CustomException("Unauthorized access", HttpStatus.FORBIDDEN);
            }

            Optional<User> userById = userRepository.findById(userDTO.getId());
            if(!userById.isPresent()) {
                throw new CustomException("Please provide valid id", HttpStatus.NOT_FOUND);
            }
            User user = userById.get();
            user.setPassword(passwordEncoder.encode(user.getUsername()));
            user.setUpdatedBy(currentUser.get());
            userRepository.save(user);
            return new ResponseDTO<>(true, "Password reset successfully");
        } catch (CustomException ce) {
            ce.printStackTrace();
            throw ce;
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Failed to reset password: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
