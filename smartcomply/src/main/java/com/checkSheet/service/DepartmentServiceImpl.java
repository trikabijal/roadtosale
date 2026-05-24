package com.checkSheet.service;

import java.util.*;
import java.util.stream.Collectors;

import com.checkSheet.constant.EmailTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.checkSheet.DAO.ChecksheetDAO;
import com.checkSheet.DAO.DepartmentDAO;
import com.checkSheet.DTO.DepartmentDTO;
import com.checkSheet.DTO.RoleDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.constant.SystemRole;
import com.checkSheet.entity.Department;
import com.checkSheet.entity.Role;
import com.checkSheet.entity.User;
import com.checkSheet.entity.UserRoleDepartment;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.DepartmentRepository;
import com.checkSheet.repository.RoleRepository;
import com.checkSheet.repository.UserRepository;
import com.checkSheet.repository.UserRoleDepartmentRepository;
import com.checkSheet.service.export.xlsx.ExcelSheet;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class DepartmentServiceImpl implements DepartmentService {

    private final PermissionService permissionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private DepartmentDAO departmentDAO;

    @Autowired
    private UserRoleDepartmentRepository userRoleDepartmentRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ChecksheetDAO checksheetDAO;

    @Autowired
    private UtilityService utilityService;


    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> createOrEditDepartment(DepartmentDTO departmentDTO) throws CustomException {
        try {
            if((Objects.equals(departmentDTO.getName(), null) || departmentDTO.getName().trim().isEmpty() ||
                    Objects.equals(departmentDTO.getUsername(), null) || departmentDTO.getUsername().trim().isEmpty() ||
                    Objects.equals(departmentDTO.getIsSubDepartment(), null))
                && Objects.equals(departmentDTO.getId(), null)
                ) {
                throw new CustomException("Please provide name and username", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if((Objects.equals(departmentDTO.getUsername(), null) || departmentDTO.getUsername().trim().isEmpty() ||
                    Objects.equals(departmentDTO.getIsSubDepartment(), null))
                    && !Objects.equals(departmentDTO.getId(), null)
            ) {
                throw new CustomException("Please provide username", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            Optional<User> loginUser = utilityService.getCurrentLoggedInUser();
            Department department = new Department();
            if(!Objects.equals(departmentDTO.getId(), null)) {
                Optional<Department> departmentById = departmentRepository.findById(departmentDTO.getId());
                if(!departmentById.isPresent()) {
                    throw new CustomException("Please provide valid id", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                department = departmentById.get();
                department.setUpdatedBy(loginUser.get());
            } else {
                department.setCreatedBy(loginUser.get());
            }


            List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(loginUser.get().getId());
            if(Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
                throw new CustomException("Invalid role", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            // Check permissions for department/subdepartment creation
            if(!departmentDTO.getIsSubDepartment()) {
                // Creating/updating a department requires DEPARTMENT_CREATE permission
                if(!permissionService.hasPermission(loginUser.get().getId(), "DEPARTMENT_CREATE")) {
                    throw new CustomException("You do not have permission to create/update department", HttpStatus.FORBIDDEN);
                }
            } else {
                // Creating/updating a subdepartment requires SUBDEPARTMENT_CREATE permission
                if(!permissionService.hasPermission(loginUser.get().getId(), "SUBDEPARTMENT_CREATE")) {
                    throw new CustomException("You do not have permission to create/update subdepartment", HttpStatus.FORBIDDEN);
                }
            }

            // AUTO-ROLE ASSIGNMENT DISABLED: Role assignment is now handled separately in user management
            // The following code block has been commented out as per requirement to decouple
            // department creation from automatic role assignment.
            /*
            // Determine which role to assign to the department owner based on what they're creating
            String newUserRoleCode = "";
            if(permissionService.hasPermission(loginUser.get().getId(), "DEPARTMENT_CREATE") && !departmentDTO.getIsSubDepartment()) {
                newUserRoleCode = "DEPT_ADMIN";
            } else if(permissionService.hasPermission(loginUser.get().getId(), "SUBDEPARTMENT_CREATE") && departmentDTO.getIsSubDepartment()) {
                newUserRoleCode = "SUBDEPT_ADMIN";
            } else {
                throw new CustomException("Invalid permission for this operation", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            */
            Optional<Department> departmentByName = departmentRepository.findByName(departmentDTO.getName());
            if (departmentByName.isPresent() && Objects.equals(departmentDTO.getId(), null)) {
                throw new CustomException("Section already exists", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if(Objects.equals(departmentDTO.getId(), null) ) {
                department.setName(departmentDTO.getName());
            } else {
                Date currentTime = new Date();
                Date createdTime = department.getCreatedAt();

                long diffInMillies = Math.abs(currentTime.getTime() - createdTime.getTime());
                long diffInHours = diffInMillies / (60 * 60 * 1000);

                if (diffInHours < 24) {
                    if(!Objects.equals(departmentDTO.getId(), null)) {
                        Optional<Department> departmentByNameId = departmentRepository.findByNameAndIdNot(departmentDTO.getName(), department.getId());
                        if(departmentByNameId.isPresent()) {
                            throw new CustomException("Section already exists", HttpStatus.UNPROCESSABLE_ENTITY);
                        }
                    }
                    department.setName(departmentDTO.getName());
                }
            }
            if (!Objects.isNull(departmentDTO.getDepartmentId()) && !Objects.equals(departmentDTO.getDepartmentId(), "")) {
                Optional<Department> tempDepartment = departmentRepository.findById(departmentDTO.getDepartmentId());
                if (!tempDepartment.isPresent()) {
                    throw new CustomException("Department not found", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                department.setDepartmentId(tempDepartment.get());
            }
            
            // AUTO-ROLE ASSIGNMENT DISABLED: The following block has been commented out.
            // Role assignment for department/section owners is now handled separately in user management.
            /*
            if(Objects.equals(departmentDTO.getId(), null)) {
                Optional<User> userByUsername = userRepository.findByUsername(departmentDTO.getUsername());
                User user = new User();
                if (!userByUsername.isPresent()) {
                    user.setUsername(departmentDTO.getUsername());
                    user.setEmail(departmentDTO.getUsername()+ "@gmail.com");
                    user.setFirstName(departmentDTO.getUsername());
                    user.setLastName(departmentDTO.getUsername());
                    userRepository.save(user);
                } else {
                    user = userByUsername.get();
                }

                Optional<UserRoleDepartment> newUserRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNullAndDepartmentIdIsNotNull(user.getId());
                if(!Objects.equals(newUserRoleByUserIdDepartment, null) && newUserRoleByUserIdDepartment.isPresent()) {
                    throw new CustomException("You have already assigned department", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                if(!Objects.equals(userRoleByUserIdDepartment, null) && !userRoleByUserIdDepartment.isEmpty() &&
                        !Objects.equals(userRoleByUserIdDepartment.get(0).getDepartmentId(), null) ) {
                    Optional<Department> departmentById = departmentRepository.findById(userRoleByUserIdDepartment.get(0).getDepartmentId().getId());
                    if(departmentById.isPresent()) {
                        department.setDepartmentId(departmentById.get());
                    }
                }
                departmentRepository.save(department);
                UserRoleDepartment userRoleDepartment = new UserRoleDepartment();
                userRoleDepartment.setDepartmentId(department);
                userRoleDepartment.setUser(user);

                Optional<com.checkSheet.entity.Role> roleByRoleCode = roleRepository.findByRoleCode(newUserRoleCode);
                if(!roleByRoleCode.isPresent()) {
                    throw new CustomException("Role not in database", HttpStatus.UNPROCESSABLE_ENTITY);
                }
                userRoleDepartment.setRole(roleByRoleCode.get());
                userRoleDepartmentRepository.save(userRoleDepartment);
            } else {
                Optional<User> userByUsername = userRepository.findByUsername(departmentDTO.getUsername());
                Optional<Role> previousRoleByRoleCode = Optional.empty();
                if(departmentDTO.getIsSubDepartment()) {
                    previousRoleByRoleCode = roleRepository.findByRoleCode("SUBDEPT_ADMIN");
                } else {
                    previousRoleByRoleCode = roleRepository.findByRoleCode("DEPT_ADMIN");
                }
                Optional<UserRoleDepartment> userByRoleIdAndDepartmentIdAndDeletedByIsNull =
                        userRoleDepartmentRepository.findByRole_IdAndDepartment_IdAndDeletedByIsNull(previousRoleByRoleCode.get().getId(), department.getId());
                if(!Objects.equals(departmentDTO.getUsername(), userByRoleIdAndDepartmentIdAndDeletedByIsNull.get().getUser().getUsername())) {
                    User user = new User();
                    if (!userByUsername.isPresent()) {
                        user.setUsername(departmentDTO.getUsername());
                        user.setEmail(departmentDTO.getUsername() + "@gmail.com");
                        user.setFirstName(departmentDTO.getUsername());
                        user.setLastName(departmentDTO.getUsername());
                        userRepository.save(user);
                    } else {
                        user = userByUsername.get();
                    }

                    userByRoleIdAndDepartmentIdAndDeletedByIsNull.ifPresent(userRoleDepartmentRepository::delete);
                    Optional<UserRoleDepartment> oldUserRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNullAndDepartmentIdIsNotNull(user.getId());
                    if (!Objects.equals(oldUserRoleByUserIdDepartment, null) && oldUserRoleByUserIdDepartment.isPresent()) {
                        throw new CustomException("You have already assigned department", HttpStatus.UNPROCESSABLE_ENTITY);
                    }

                    Optional<UserRoleDepartment> newUserRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNullAndDepartmentIdIsNotNull(user.getId());
                    if (!Objects.equals(newUserRoleByUserIdDepartment, null) && newUserRoleByUserIdDepartment.isPresent()) {
                        throw new CustomException("You have already assigned department", HttpStatus.UNPROCESSABLE_ENTITY);
                    }
                    if (!Objects.equals(userRoleByUserIdDepartment, null) && !userRoleByUserIdDepartment.isEmpty() &&
                            !Objects.equals(userRoleByUserIdDepartment.get(0).getDepartmentId(), null)) {
                        Optional<Department> departmentById = departmentRepository.findById(userRoleByUserIdDepartment.get(0).getDepartmentId().getId());
                        if (departmentById.isPresent()) {
                            department.setDepartmentId(departmentById.get());
                        }
                    }
                    departmentRepository.save(department);
                    UserRoleDepartment userRoleDepartment = new UserRoleDepartment();
                    userRoleDepartment.setDepartmentId(department);
                    userRoleDepartment.setUser(user);

                    Optional<com.checkSheet.entity.Role> roleByRoleCode = roleRepository.findByRoleCode(newUserRoleCode);
                    if (!roleByRoleCode.isPresent()) {
                        throw new CustomException("Role not in database", HttpStatus.UNPROCESSABLE_ENTITY);
                    }
                    userRoleDepartment.setRole(roleByRoleCode.get());
                    userRoleDepartmentRepository.save(userRoleDepartment);
                    departmentRepository.save(department);
                }
            }
            */
            
            // Just save the department without auto-role assignment
            departmentRepository.save(department);
            
            if(Objects.equals(departmentDTO.getId(), null) && !departmentDTO.getIsSubDepartment()) {
                return new ResponseDTO<>(true, "Department created successfully");
            } else if(!Objects.equals(departmentDTO.getId(), null) && !departmentDTO.getIsSubDepartment()) {
                return new ResponseDTO<>(true, "Department updated successfully");
            } else if(Objects.equals(departmentDTO.getId(), null) && departmentDTO.getIsSubDepartment()) {
                return new ResponseDTO<>(true, "Section created successfully");
            } else {
                return new ResponseDTO<>(true, "Section updated successfully");
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException(e.getMessage(), HttpStatus.UNPROCESSABLE_ENTITY);
        }
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> createDepartmentAdmin(DepartmentDTO departmentDTO) throws CustomException {
        if(Objects.equals(departmentDTO.getId(), null)){
            throw new CustomException("Please provide Department ID", HttpStatus.UNPROCESSABLE_ENTITY);
        }else if(Objects.equals(departmentDTO.getUsername(), null) || departmentDTO.getUsername().trim().isEmpty()){
            throw new CustomException("Please provide Username", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Optional<Department> department = departmentRepository.findByIdAndDepartmentIdIsNull(departmentDTO.getId());
        if(!department.isPresent()) {
            throw new CustomException("Please provide valid id", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        // Check user is super admin or not -- start
        Optional<User> loginUser = utilityService.getCurrentLoggedInUser();
        List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(loginUser.get().getId());
        if(Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
            throw new CustomException("Invalid role", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        
        // Check user has permission to create department
        if(!permissionService.hasPermission(loginUser.get().getId(), "DEPARTMENT_CREATE")) {
            throw new CustomException("You can not create/update department", HttpStatus.UNPROCESSABLE_ENTITY);
        }

       // check Dept admin role is available or not -- start
        Optional<Role> deptAdminRoleCode = roleRepository.findByRoleCode("DEPT_ADMIN");
        if(!deptAdminRoleCode.isPresent()) {
            throw new CustomException("Invalid role", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        // check Dept admin role is available or not -- end

        //Check user is exist or not and if not exist then create and map it to role and department -- start
        Optional<User> userByUsername = userRepository.findByUsername(departmentDTO.getUsername());
        User user;
        if (userByUsername.isPresent()) {
            user = userByUsername.get();
            Optional<UserRoleDepartment> newUserRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndRole_IdAndDepartmentId_Id(user.getId(),deptAdminRoleCode.get().getId(),department.get().getId());
            if(!Objects.equals(newUserRoleByUserIdDepartment, null) && newUserRoleByUserIdDepartment.isPresent()) {
                throw new CustomException("You have already assigned department", HttpStatus.UNPROCESSABLE_ENTITY);
            }
        } else {
//            user = createUsernameUser(departmentDTO,loginUser.get());
            // User should be exist bcz it is created by validateAndSaveUsername API
            throw new CustomException("User is not exist!!", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        createUserRoleDepartment(user,deptAdminRoleCode.get(), department.get(),loginUser.get());
        utilityService.sendUserCreationEmail(List.of(user.getId()), EmailTemplate.CREATE_DEPT_ADMIN, department.get(),List.of(loginUser.get().getId()));
        return new ResponseDTO<>(true, "Department Admin is created successfully");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> deleteDepartmentAdmin(DepartmentDTO departmentDTO) throws CustomException{
        if(Objects.equals(departmentDTO.getId(), null)){
            throw new CustomException("Please provide User Role Department ID", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        User loginUser = utilityService.getCurrentLoggedInUser().get();
        List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(loginUser.getId());
        if(Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
            throw new CustomException("Invalid role", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        
        // Check user has permission to delete department admin
        if(!permissionService.hasPermission(loginUser.getId(), "DEPARTMENT_EDIT")) {
            throw new CustomException("You are not authorized to delete Department Admin.", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        
        //Delete old records -- start
        Optional<UserRoleDepartment> userRoleDepartment = userRoleDepartmentRepository.findById(departmentDTO.getId());
        if(userRoleDepartment.isPresent()){
            userRoleDepartmentRepository.delete(userRoleDepartment.get());
        }
        //Delete old records -- end
        return new ResponseDTO<>(true, "Department Admin has been deleted successfully");
    }

    @Override
    public ResponseDTO<?> editDepartmentAdmin(DepartmentDTO departmentDTO) throws CustomException{

        DepartmentDTO addDeptDTO = new DepartmentDTO();
        addDeptDTO.setId(departmentDTO.getDepartmentId());
        addDeptDTO.setUsername(departmentDTO.getUsername());
        createDepartmentAdmin(addDeptDTO);

        DepartmentDTO delDeptDTO = new DepartmentDTO();
        delDeptDTO.setId(departmentDTO.getId());
        deleteDepartmentAdmin(delDeptDTO);

        return new ResponseDTO<>(true, "Department Admin has been updated successfully");
    }
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ResponseDTO<?> createOrEditSectionAndAdmin(DepartmentDTO departmentDTO) throws CustomException{
        Department department = new Department();
        User loginUser = utilityService.getCurrentLoggedInUser().get();
        if(Objects.equals(departmentDTO.getUsername(), null) || departmentDTO.getUsername().trim().isEmpty()){
            throw new CustomException("Please provide Username", HttpStatus.UNPROCESSABLE_ENTITY);
        }else if(Objects.equals(departmentDTO.getDepartmentId(), null)){
            throw new CustomException("Please provide Department ID", HttpStatus.UNPROCESSABLE_ENTITY);
        }else if(Objects.equals(departmentDTO.getName(), null) || departmentDTO.getName().trim().isEmpty()){
            throw new CustomException("Please provide department name", HttpStatus.UNPROCESSABLE_ENTITY);
        }else if(!Objects.equals(departmentDTO.getId(), null) ) {
            Optional<Department> sectionEditOpt = departmentRepository.findById(departmentDTO.getId());
            if(!sectionEditOpt.isPresent()) {
                throw new CustomException("Please provide valid Section id", HttpStatus.UNPROCESSABLE_ENTITY);
            }else{
                department = sectionEditOpt.get();
//                Date currentTime = new Date();
//                Date createdTime = department.getCreatedAt();
//
//                long diffInMillies = Math.abs(currentTime.getTime() - createdTime.getTime());
//                long diffInHours = diffInMillies / (60 * 60 * 1000);
//
//                if (diffInHours > 24) {
//                    throw new CustomException("Department can not be updated after 24 hours!!", HttpStatus.UNPROCESSABLE_ENTITY);
//                }
                department.setUpdatedBy(loginUser);

            }
        }else{
            department.setCreatedBy(loginUser);
        }

        // Check user has permission to create/update section
        List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(loginUser.getId());
        if(Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
            throw new CustomException("Invalid role", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        
        if(!permissionService.hasPermission(loginUser.getId(), "SUBDEPARTMENT_CREATE")) {
            throw new CustomException("You can not create/update section", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        
        // check Section admin role is available or not -- start
        Optional<Role> subDeptAdminRole = roleRepository.findByRoleCode("SUBDEPT_ADMIN");
        if(!subDeptAdminRole.isPresent()) {
            throw new CustomException("Invalid role", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Optional<UserRoleDepartment> userByRoleIdAndDepartmentIdAndDeletedByIsNull = userRoleDepartmentRepository.findByRole_IdAndDepartment_IdAndDeletedByIsNull(subDeptAdminRole.get().getId(), department.getId());
        userByRoleIdAndDepartmentIdAndDeletedByIsNull.ifPresent(userRoleDepartmentRepository::delete);
        // check Section admin role is available or not -- end

        // create Department with current user's departement as parent department -- start
        List<Long> currentUserDepts = userRoleByUserIdDepartment.stream().filter(urd -> !Objects.isNull(urd.getDepartmentId())).map(urd -> urd.getDepartmentId().getId()).toList();
        if (currentUserDepts.contains(departmentDTO.getDepartmentId())) {
            //            Department selectedDept = userRoleByUserIdDepartment.get(0).getDepartmentId();
            Department selectedDept = departmentRepository.findById(departmentDTO.getDepartmentId()).get();
            if (selectedDept.getDepartmentId() != null) {
                throw new CustomException("Department is already section", HttpStatus.UNPROCESSABLE_ENTITY);
            } else {
                Optional<Department> departmentByDepartmentIdAndName = departmentRepository.findByDepartmentIdAndName(selectedDept, departmentDTO.getName());
                if (departmentByDepartmentIdAndName.isPresent() && Objects.equals(departmentDTO.getId(), null)) {
                    throw new CustomException("Section already exists", HttpStatus.UNPROCESSABLE_ENTITY);
                }
            }
            department.setDepartmentId(selectedDept);
            department.setName(departmentDTO.getName());
            departmentRepository.save(department);
            departmentRepository.flush();
        } else {
            throw new CustomException("Current user's Department is not exist", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        Optional<User> userByUsername = userRepository.findByUsernameIgnoreCase(departmentDTO.getUsername());
        User user;
        if (userByUsername.isPresent()) {
            user = userByUsername.get();
            Optional<UserRoleDepartment> newUserRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndRole_IdAndDepartmentId_Id(user.getId(),subDeptAdminRole.get().getId(),department.getId());
            if(!Objects.equals(newUserRoleByUserIdDepartment, null) && newUserRoleByUserIdDepartment.isPresent()) {
                throw new CustomException("You have already assigned department", HttpStatus.UNPROCESSABLE_ENTITY);
            }
        } else {
//            user = createUsernameUser(departmentDTO,loginUser);
            throw new CustomException("Username User is not exist", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        createUserRoleDepartment(user,subDeptAdminRole.get(), department,loginUser);
        utilityService.sendUserCreationEmail(List.of(user.getId()), EmailTemplate.CREATE_SECTION_ADMIN, department,List.of(loginUser.getId()));
        if(Objects.equals(departmentDTO.getId(), null) ) {
            return new ResponseDTO<>(true, "Section is created successfully");
        } else {
            return new ResponseDTO<>(true, "Section is edited successfully");
        }
    }

    private UserRoleDepartment createUserRoleDepartment(User user, Role role, Department department, User loginUser) {
        UserRoleDepartment userRoleDepartment = new UserRoleDepartment();
        userRoleDepartment.setDepartmentId(department);
        userRoleDepartment.setUser(user);
        userRoleDepartment.setRole(role);
        userRoleDepartment.setCreatedBy(loginUser);
        userRoleDepartmentRepository.save(userRoleDepartment);
        userRoleDepartmentRepository.flush();
        return userRoleDepartment;
    }

    private User createUsernameUser(DepartmentDTO departmentDTO, User loginUser) {
        User user = new User();
        user.setUsername(departmentDTO.getUsername());
        user.setEmail(departmentDTO.getUsername() + "@gmail.com");
        user.setFirstName(departmentDTO.getUsername());
        user.setLastName(departmentDTO.getUsername());
        user.setCreatedBy(loginUser);
        userRepository.save(user);
        userRepository.flush();
        return user;
    }

    @Override
    public ResponseDTO<?> getDepartments(DepartmentDTO departmentDTO) throws CustomException {
        try {
            List<DepartmentDTO> allDepartments = departmentDAO.getAllDepartments(departmentDTO.getId());
            if(!Objects.equals(departmentDTO.getId(), null)) {
                Date currentTime = new Date();
                for (DepartmentDTO tempDepartmentDTO : allDepartments) {
                    if (tempDepartmentDTO.getCreatedAt() != null) {
                        Calendar calendar = Calendar.getInstance();
                        calendar.setTime(tempDepartmentDTO.getCreatedAt());
                        calendar.add(Calendar.HOUR_OF_DAY, 24);
                        Date createdTimePlus24Hours = calendar.getTime();
                        tempDepartmentDTO.setIsEditable(currentTime.before(createdTimePlus24Hours));
                    }
                }
            }
            return new ResponseDTO<>(true, "All department has been fetched", allDepartments);
        } catch (Exception e) {
            e.printStackTrace();
            return new ResponseDTO<>(false, "Something went wrong :" + e.getMessage ());
        }
    }

    @Override
    public ResponseDTO<?> getSectionHead(DepartmentDTO departmentDTO) throws CustomException {
        try {
            List<DepartmentDTO> allDepartments = departmentDAO.getSectionHead(departmentDTO.getId());
            return new ResponseDTO<>(true, "All department has been fetched", allDepartments);
        } catch (Exception e) {
            e.printStackTrace();
            return new ResponseDTO<>(false, "Something went wrong :" + e.getMessage ());
        }
    }

    @Override
    public ResponseDTO<?> searchDepartments(DepartmentDTO departmentDTO) throws CustomException {
        try {
            if(Objects.equals(departmentDTO.getCurrentPage(), null) || Objects.equals(departmentDTO.getCurrentPage(), "") ||
                    Objects.equals(departmentDTO.getPerPageRecord(), null) || Objects.equals(departmentDTO.getPerPageRecord(), "")) {
                return new ResponseDTO<>(false, "Please provide currentPage, perPageRecord");
            }
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String username = authentication.getName();
            Optional<User> loginUser = userRepository.findByUsernameIgnoreCase(username);
            Page<DepartmentDTO> enquiryDTOS = departmentDAO.searchDepartments(departmentDTO, "department");
            List<DepartmentDTO> content = enquiryDTOS.getContent();
//            Date currentTime = new Date();
//            content.forEach(dept -> {
//                if (dept.getCreatedAt() != null) {
//                    Calendar calendar = Calendar.getInstance();
//                    calendar.setTime(dept.getCreatedAt());
//                    calendar.add(Calendar.HOUR_OF_DAY, 24);
//                    Date createdTimePlus24Hours = calendar.getTime();
//                    dept.setIsEditable(currentTime.before(createdTimePlus24Hours));
//                }
//            });

            ResponseDTO<?> responseDTO = new ResponseDTO<>(true, "Departments fetched successfully", content);
            responseDTO.setCurrentPage(Math.toIntExact(departmentDTO.getCurrentPage()));
            responseDTO.setPageSize(Math.toIntExact(departmentDTO.getPerPageRecord()));
            responseDTO.setTotalRecords((enquiryDTOS.getTotalElements()));
            responseDTO.setTotalPages(Math.toIntExact(enquiryDTOS.getTotalPages()));
            return responseDTO;
        } catch(Exception e) {
            e.printStackTrace();
            return new ResponseDTO<>(false, e.getMessage());
        }
    }

    @Override
    public void downloadDepartments(DepartmentDTO departmentDTO, HttpServletResponse response) throws CustomException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String username = authentication.getName();
            Optional<User> loginUser = userRepository.findByUsernameIgnoreCase(username);
            List<DepartmentDTO> enquiryDTOS = departmentDAO.searchDepartmentsForDownload(departmentDTO, "department");
            ExcelSheet excelSheet = new ExcelSheet();
            excelSheet.generateExcelFileForDepartments(response, enquiryDTOS);
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public ResponseDTO<?> searchSections(DepartmentDTO departmentDTO) throws CustomException {
        try {
            if(Objects.equals(departmentDTO.getCurrentPage(), null) || Objects.equals(departmentDTO.getCurrentPage(), "") ||
                    Objects.equals(departmentDTO.getPerPageRecord(), null) || Objects.equals(departmentDTO.getPerPageRecord(), "")) {
                return new ResponseDTO<>(false, "Please provide currentPage, perPageRecord");
            }
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String username = authentication.getName();
            Optional<User> loginUser = userRepository.findByUsernameIgnoreCase(username);
            List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(loginUser.get().getId());
            if(Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
                throw new CustomException("Invalid role", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if(!Objects.equals(userRoleByUserIdDepartment.get(0), null) && !Objects.equals(userRoleByUserIdDepartment.get(0).getDepartmentId(), null)
                    && !Objects.equals(userRoleByUserIdDepartment.get(0).getDepartmentId().getId(), null)) {
                departmentDTO.setId(userRoleByUserIdDepartment.get(0).getDepartmentId().getId());
            }
//            departmentDTO.setIsSubDepartment(true);
            // Use permission-based scope filtering (null = global access, empty = no access)
            List<Long> allowedDepartmentIds = permissionService.getAllowedDepartmentIds(loginUser.get().getId());

            Page<DepartmentDTO> enquiryDTOS;
            if(departmentDTO.getIsSubDepartment() != null && !departmentDTO.getIsSubDepartment()){
                List<Department> sections = new ArrayList<>();
                if (userRoleByUserIdDepartment.isEmpty()){
                    throw new CustomException("You have no roles");
                }else if(allowedDepartmentIds != null && allowedDepartmentIds.isEmpty()){
                    // Empty list means NO access (null means global access)
                    throw new CustomException("You have no permissions assigned. Please contact your administrator.", HttpStatus.FORBIDDEN);
                }else if(allowedDepartmentIds == null){
                    // Global access - get all sections for requested departments
                    List<Long> requestedDeptIds = departmentDTO.getDepartmentIds();
                    sections = departmentRepository.findByDepartmentId_IdIn(requestedDeptIds);
                }else {
                    // Filter by allowed scope
                    List<Long> requestedDeptIds = departmentDTO.getDepartmentIds();
                    List<Long> allowedRequestedDepts = requestedDeptIds.stream()
                        .filter(allowedDepartmentIds::contains)
                        .collect(Collectors.toList());
                    
                    if (!allowedRequestedDepts.isEmpty()) {
                        sections = departmentRepository.findByDepartmentId_IdIn(allowedRequestedDepts);
                    }
                }
                if (sections.isEmpty()) {
                    Pageable pageable = PageRequest.of(Math.toIntExact(departmentDTO.getCurrentPage()), Math.toIntExact(departmentDTO.getPerPageRecord()));
                    enquiryDTOS = new PageImpl<>(new ArrayList<>(), pageable, 0);
                } else {
                    List<Long> sectionIds = sections.stream().distinct().map(Department::getId).collect(Collectors.toList());
                    departmentDTO.setDepartmentIds(sectionIds);
                    enquiryDTOS = departmentDAO.searchDepartments(departmentDTO, "section");
                }
            }else{
                enquiryDTOS = departmentDAO.searchDepartments(departmentDTO, "section");
            }
            List<DepartmentDTO> content = enquiryDTOS.getContent();
//            Date currentTime = new Date();
//            content.forEach(dept -> {
//                if (dept.getCreatedAt() != null) {
//                    Calendar calendar = Calendar.getInstance();
//                    calendar.setTime(dept.getCreatedAt());
//                    calendar.add(Calendar.HOUR_OF_DAY, 24);
//                    Date createdTimePlus24Hours = calendar.getTime();
//                    dept.setIsEditable(currentTime.before(createdTimePlus24Hours));
//                }
//            });

            ResponseDTO<List<DepartmentDTO>> responseDTO = new ResponseDTO<>(true, "Sections fetched successfully", content);
            responseDTO.setCurrentPage(Math.toIntExact(departmentDTO.getCurrentPage()));
            responseDTO.setPageSize(Math.toIntExact(departmentDTO.getPerPageRecord()));
            responseDTO.setTotalRecords((enquiryDTOS.getTotalElements()));
            responseDTO.setTotalPages(Math.toIntExact(enquiryDTOS.getTotalPages()));
            return responseDTO;
        } catch(Exception e) {
            e.printStackTrace();
            return new ResponseDTO<>(false, e.getMessage());
        }
    }

    @Override
    public void downloadSections(DepartmentDTO departmentDTO, HttpServletResponse response) throws CustomException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String username = authentication.getName();
            Optional<User> loginUser = userRepository.findByUsernameIgnoreCase(username);
            
            if (!loginUser.isPresent()) {
                throw new CustomException("User not found", HttpStatus.UNAUTHORIZED);
            }
            
            List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository
                .findByUser_IdAndDeletedByIsNull(loginUser.get().getId());
            
            if (Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
                throw new CustomException("Invalid role", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            if (!Objects.equals(userRoleByUserIdDepartment.get(0), null) && 
                !Objects.equals(userRoleByUserIdDepartment.get(0).getDepartmentId(), null) &&
                !Objects.equals(userRoleByUserIdDepartment.get(0).getDepartmentId().getId(), null)) {
                departmentDTO.setId(userRoleByUserIdDepartment.get(0).getDepartmentId().getId());
            }
            
//            departmentDTO.setIsSubDepartment(true);
            if(departmentDTO.getIsSubDepartment() != null && !departmentDTO.getIsSubDepartment()){
                // Use permission-based scope filtering (null = global access)
                List<Long> allowedDepartmentIds = permissionService.getAllowedDepartmentIds(loginUser.get().getId());
                List<Long> requestedDeptIds = departmentDTO.getDepartmentIds();
                
                if (allowedDepartmentIds != null && !allowedDepartmentIds.isEmpty()) {
                    // Filter to only allowed departments (non-null, non-empty = scoped access)
                    requestedDeptIds = requestedDeptIds.stream()
                        .filter(allowedDepartmentIds::contains)
                        .collect(Collectors.toList());
                }
                // If allowedDepartmentIds is null, it means global access - use all requested departments
                
                List<Department> sections = departmentRepository.findByDepartmentId_IdIn(requestedDeptIds);
                List<Long> sectionIds = sections.stream().map(Department::getId).collect(Collectors.toList());
                departmentDTO.setDepartmentIds(sectionIds);
            }
            List<DepartmentDTO> enquiryDTOS = departmentDAO.searchDepartmentsForDownload(departmentDTO, "section");
            
            if (enquiryDTOS == null || enquiryDTOS.isEmpty()) {
                throw new CustomException("No data found", HttpStatus.NOT_FOUND);
            }

            // Set response headers
            response.setContentType("application/vnd.ms-excel");
            response.setHeader("Content-Disposition", "attachment; filename=Sections.xlsx");
            response.setHeader("Pragma", "public");
            response.setHeader("Cache-Control", "no-store");
            response.addHeader("Cache-Control", "max-age=0");
            
            ExcelSheet excelSheet = new ExcelSheet();
            excelSheet.generateExcelFileForSection(response, enquiryDTOS);
            
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error generating sections report: " + e.getMessage(), 
                                    HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public ResponseDTO<?> searchUsers(DepartmentDTO departmentDTO) throws CustomException {
        try {
            if(Objects.equals(departmentDTO.getCurrentPage(), null) || Objects.equals(departmentDTO.getCurrentPage(), "") ||
                    Objects.equals(departmentDTO.getPerPageRecord(), null) || Objects.equals(departmentDTO.getPerPageRecord(), "")) {
                return new ResponseDTO<>(false, "Please provide currentPage, perPageRecord");
            }
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String username = authentication.getName();
            Optional<User> loginUser = userRepository.findByUsernameIgnoreCase(username);
            List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(loginUser.get().getId());
            if(Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
                throw new CustomException("Invalid role", HttpStatus.UNPROCESSABLE_ENTITY);
            }

            // Use permission-based scope filtering
            List<Long> allowedDepartmentIds = permissionService.getAllowedDepartmentIds(loginUser.get().getId());
            List<String> allRoles = userRoleByUserIdDepartment.stream().map(roledepartment -> roledepartment.getRole().getRoleCode()).collect(Collectors.toList());
            

            if(!Objects.equals(userRoleByUserIdDepartment.get(0), null) && !Objects.equals(userRoleByUserIdDepartment.get(0).getDepartmentId(), null)
                    && !Objects.equals(userRoleByUserIdDepartment.get(0).getDepartmentId().getId(), null)) {
                departmentDTO.setId(userRoleByUserIdDepartment.get(0).getDepartmentId().getId());
            }

            // INSERT_YOUR_CODE
            System.out.println("departmentDTO.getDepartmentIds(): " + departmentDTO.getDepartmentIds());
            System.out.println("departmentDTO.getIsSubDepartment(): " + departmentDTO.getIsSubDepartment());
            
            if(!Objects.equals(departmentDTO.getDepartmentIds(), null) && !departmentDTO.getDepartmentIds().isEmpty() && !Objects.equals(departmentDTO.getIsSubDepartment(), null) && !departmentDTO.getIsSubDepartment()) {
                // Set departmentIds based on user's allowed scope (permission-based, null = global access)
                if (allowedDepartmentIds != null && allowedDepartmentIds.isEmpty()) {
                    // Empty list means NO access (null means global access)
                    throw new CustomException("You have no permissions assigned. Please contact your administrator.", HttpStatus.FORBIDDEN);
                } else if (allowedDepartmentIds == null) {
                    // Global access - use all requested departments
                    List<Long> requestedDeptIds = departmentDTO.getDepartmentIds();
                    List<Department> sections = departmentRepository.findByDepartmentId_IdIn(requestedDeptIds);
                    List<Long> sectionIds = sections.stream().map(Department::getId).collect(Collectors.toList());
                    System.out.println("Section IDs (global access): "+sectionIds);
                    departmentDTO.setDepartmentIds(sectionIds);
                } else {
                    // Filter requested departments by allowed scope
                    List<Long> requestedDeptIds = departmentDTO.getDepartmentIds();
                    List<Long> allowedRequestedDepts = requestedDeptIds.stream()
                            .filter(allowedDepartmentIds::contains)
                            .collect(Collectors.toList());
                    
                    if (!allowedRequestedDepts.isEmpty()) {
                        List<Department> sections = departmentRepository.findByDepartmentId_IdIn(allowedRequestedDepts);
                        List<Long> sectionIds = sections.stream().map(Department::getId).collect(Collectors.toList());
                        System.out.println("Section IDs : "+sectionIds);
                        departmentDTO.setDepartmentIds(sectionIds);
                    } else {
                        departmentDTO.setDepartmentIds(new ArrayList<>());
                    }
                }
            }
            System.out.println("Role IDs : "+departmentDTO.getRoleIds());
            /* if(departmentDTO.getRoleIds() == null || departmentDTO.getRoleIds().isEmpty()){
                Optional<Role> operatorRoleOpt = roleRepository.findByRoleCode("OPERATOR");
                operatorRoleOpt.ifPresent(role -> departmentDTO.setRoleIds(List.of(role.getId())));
            } */
            Page<DepartmentDTO> enquiryDTOS = departmentDAO.searchUsers(departmentDTO, "user", allRoles);
            List<DepartmentDTO> content = enquiryDTOS.getContent();

            List<Map<String, Object>> responseList = new ArrayList<>();

            for (DepartmentDTO dept : content) {
                Map<String, Object> userMap = new LinkedHashMap<>();
                userMap.put("userid", dept.getUserId());
                userMap.put("username", dept.getUsername());
                userMap.put("email", dept.getEmail());
                userMap.put("firstName", dept.getFirstName());
                userMap.put("lastName", dept.getLastName());
                userMap.put("mobile", dept.getMobile());
                userMap.put("userRoleDepartmentId", dept.getUserRoleDepartmentId());

                if (dept.getRoleCode() != null && !Objects.equals(dept.getRoleCode(), SystemRole.SUPER_ADMIN)) {
                    userMap.put("isEditable", true);
                    userMap.put("isDeletable", true);
                }else{
                    userMap.put("isEditable", false);
                    userMap.put("isDeletable", false);
                }

                // Parse Roles
                List<Map<String, Object>> rolesList = new ArrayList<>();
                if (dept.getRoleIdsStr() != null && !dept.getRoleIdsStr().isEmpty()) {
                    String[] ids = dept.getRoleIdsStr().split(",");
                    String[] names = dept.getRoleName() != null ? dept.getRoleName().split(",") : new String[0];
                    // String[] codes = dept.getRoleCode() != null ? dept.getRoleCode().split(",") : new String[0];
                    
                    for (int i = 0; i < ids.length; i++) {
                        Map<String, Object> rMap = new HashMap<>();
                        rMap.put("role_id", Long.parseLong(ids[i].trim()));
                        if (i < names.length) rMap.put("role_name", names[i].trim());
                        rolesList.add(rMap);
                    }
                }
                userMap.put("roles", rolesList);

                // Parse Departments
                List<Map<String, Object>> departmentsList = new ArrayList<>();
                if (dept.getDepartmentIdsStr() != null && !dept.getDepartmentIdsStr().isEmpty()) {
                    String[] ids = dept.getDepartmentIdsStr().split(",");
                    String[] names = dept.getDepartmentName() != null ? dept.getDepartmentName().split(",") : new String[0];
                    for (int i = 0; i < ids.length; i++) {
                         if (ids[i].trim().isEmpty()) continue;
                        Map<String, Object> dMap = new HashMap<>();
                        dMap.put("departmentid", Long.parseLong(ids[i].trim()));
                        if (i < names.length) dMap.put("department_name", names[i].trim());
                        departmentsList.add(dMap);
                    }
                }
                userMap.put("departments", departmentsList);

                // Parse Sections
                List<Map<String, Object>> sectionsList = new ArrayList<>();
                if (dept.getSectionIdsStr() != null && !dept.getSectionIdsStr().isEmpty()) {
                    String[] ids = dept.getSectionIdsStr().split(",");
                    String[] names = dept.getSectionName() != null ? dept.getSectionName().split(",") : new String[0];
                    for (int i = 0; i < ids.length; i++) {
                        if (ids[i].trim().isEmpty()) continue;
                        Map<String, Object> sMap = new HashMap<>();
                        sMap.put("sectionid", Long.parseLong(ids[i].trim()));
                        if (i < names.length) sMap.put("section_name", names[i].trim());
                        sectionsList.add(sMap);
                    }
                }
                userMap.put("sections", sectionsList);
                responseList.add(userMap);
            }

            ResponseDTO<List<Map<String, Object>>> responseDTO = new ResponseDTO<>(true, "Users fetched successfully", responseList);
            responseDTO.setCurrentPage(Math.toIntExact(departmentDTO.getCurrentPage()));
            responseDTO.setPageSize(Math.toIntExact(departmentDTO.getPerPageRecord()));
            responseDTO.setTotalRecords((enquiryDTOS.getTotalElements()));
            responseDTO.setTotalPages(Math.toIntExact(enquiryDTOS.getTotalPages()));
            return responseDTO;
        } catch(Exception e) {
            e.printStackTrace();
            return new ResponseDTO<>(false, e.getMessage());
        }
    }

    @Override
    public void downloadUsers(DepartmentDTO departmentDTO, HttpServletResponse response) throws CustomException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String username = authentication.getName();
            Optional<User> loginUser = userRepository.findByUsernameIgnoreCase(username);
            List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(loginUser.get().getId());
            if(Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
                throw new CustomException("Invalid role", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            if(!Objects.equals(userRoleByUserIdDepartment.get(0), null) && !Objects.equals(userRoleByUserIdDepartment.get(0).getDepartmentId(), null)
                    && !Objects.equals(userRoleByUserIdDepartment.get(0).getDepartmentId().getId(), null)) {
                departmentDTO.setId(userRoleByUserIdDepartment.get(0).getDepartmentId().getId());
            }
            if(!Objects.equals(departmentDTO.getDepartmentIds(), null) && !departmentDTO.getDepartmentIds().isEmpty() && !Objects.equals(departmentDTO.getIsSubDepartment(), null) && !departmentDTO.getIsSubDepartment()) {
                // Use permission-based scope filtering (null = global access)
                List<Long> allowedDepartmentIds = permissionService.getAllowedDepartmentIds(loginUser.get().getId());
                List<Long> requestedDeptIds = departmentDTO.getDepartmentIds();
                
                if (allowedDepartmentIds != null && !allowedDepartmentIds.isEmpty()) {
                    // Filter to only allowed departments (non-null, non-empty = scoped access)
                    requestedDeptIds = requestedDeptIds.stream()
                        .filter(allowedDepartmentIds::contains)
                        .collect(Collectors.toList());
                }
                // If allowedDepartmentIds is null, it means global access - use all requested departments
                
                List<Department> sections = departmentRepository.findByDepartmentId_IdIn(requestedDeptIds);
                if(!sections.isEmpty()) {
                    List<Long> sectionIds = sections.stream().map(Department::getId).collect(Collectors.toList());
                    departmentDTO.setDepartmentIds(sectionIds);
                }
            }
            if(departmentDTO.getRoleIds() == null || departmentDTO.getRoleIds().isEmpty()){
                Optional<Role> operatorRoleOpt = roleRepository.findByRoleCode("OPERATOR");
                operatorRoleOpt.ifPresent(role -> departmentDTO.setRoleIds(List.of(role.getId())));
            }
            List<DepartmentDTO> enquiryDTOS = departmentDAO.searchUsersForDownload(departmentDTO, "user");
            ExcelSheet excelSheet = new ExcelSheet();
            excelSheet.generateExcelFileForUsers(response, enquiryDTOS);
        } catch(Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public ResponseDTO<?> getDepartments() throws CustomException {
        try {
            return new ResponseDTO<>(true, "All department has been fetched", departmentDAO.getDepartments());
        } catch (Exception e) {
            e.printStackTrace();
            return new ResponseDTO<>(false, "Something went wrong :" + e.getMessage ());
        }
    }

    @Override
    public ResponseDTO<?> getUserDepartmentsForList() {
        try {
            User currentUser = utilityService.getCurrentLoggedInUser().orElseThrow(() -> new CustomException("User is not authorized.",HttpStatus.UNAUTHORIZED));
            List<UserRoleDepartment> userRoleDepartments = currentUser.getUserRoleDepartments().stream().filter(urd -> List.of(SystemRole.SUPER_ADMIN, "SUBDEPT_ADMIN", "DEPT_ADMIN").contains(urd.getRole().getRoleCode())).toList();
            // Use permission-based scope filtering (null = global access, empty = no access)
            List<Long> allowedDepartmentIds = permissionService.getAllowedDepartmentIds(currentUser.getId());
            List<Department> departments,sections;
            if (userRoleDepartments.isEmpty()){
                throw new CustomException("You have no roles");
            }else if(allowedDepartmentIds != null && allowedDepartmentIds.isEmpty()){
                // Empty list means NO access (null means global access)
                throw new CustomException("You have no permissions assigned. Please contact your administrator.", HttpStatus.FORBIDDEN);
            }else if(allowedDepartmentIds == null){
                // Global access - get all departments
                departments = departmentRepository.findByDepartmentIdIsNull();
            }else{
                // Filter by allowed scope
                departments = departmentRepository.findByIdInAndDepartmentIdIsNull(allowedDepartmentIds);
                sections = departmentRepository.findByIdInAndDepartmentIdIsNotNull(allowedDepartmentIds);
                for(Department dept : sections){
                    departments.add(dept.getDepartmentId());
                }
                departments = departments.stream().distinct().toList();
            }
            List<DepartmentDTO> departmentDTOS = departments.stream().map(dep -> utilityService.setDepartmentDTO(dep)).toList();
            return new ResponseDTO<>(true, "All Sections have been fetched", departmentDTOS);
        } catch(Exception e) {
            e.printStackTrace();
            return new ResponseDTO<>(false, "Something went wrong :" + e.getMessage ());
        }
    }
    @Override
    public ResponseDTO<?> getUserDepartments() {
        try {
            User currentUser = utilityService.getCurrentLoggedInUser().orElseThrow(() -> new CustomException("User is not authorized.",HttpStatus.UNAUTHORIZED));
            List<UserRoleDepartment> userRoleDepartments = currentUser.getUserRoleDepartments().stream().filter(urd -> List.of(SystemRole.SUPER_ADMIN, "SUBDEPT_ADMIN", "DEPT_ADMIN").contains(urd.getRole().getRoleCode())).toList();
            // Use permission-based scope filtering (null = global access, empty = no access)
            List<Long> allowedDepartmentIds = permissionService.getAllowedDepartmentIds(currentUser.getId());
            System.out.println("Allowed Department IDs: " + allowedDepartmentIds);

            List<Department> departments = new ArrayList<>();
            List<Department> sections = new ArrayList<>();
            
            if (userRoleDepartments.isEmpty()){
                System.out.println("User has no roles");
                throw new CustomException("You have no roles",HttpStatus.BAD_REQUEST);
            }else{
                System.out.println("User has roles");

                if(allowedDepartmentIds != null && allowedDepartmentIds.isEmpty()){
                    // Empty list means NO access (null means global access)
                    throw new CustomException("You have no permissions assigned. Please contact your administrator.", HttpStatus.FORBIDDEN);
                }else if(allowedDepartmentIds == null){
                    // Global access - get all departments
                    departments = departmentRepository.findByDepartmentIdIsNull();
                }else{
                    // Filter by allowed scope
                    departments = departmentRepository.findByIdInAndDepartmentIdIsNull(allowedDepartmentIds);
                    sections = departmentRepository.findByIdInAndDepartmentIdIsNotNull(allowedDepartmentIds);
                    for(Department dept : sections){
                        departments.add(dept.getDepartmentId());
                    }
                    departments = departments.stream().distinct().toList();
                }

            }
            List<DepartmentDTO> departmentDTOS = departments.stream().map(dep -> utilityService.setDepartmentDTO(dep)).toList();
            return new ResponseDTO<>(true, "All Sections have been fetched", departmentDTOS);
        } catch(Exception e) {
            e.printStackTrace();
            return new ResponseDTO<>(false, "Something went wrong :" + e.getMessage ());
        }
    }

    @Override
    public ResponseDTO<?> getUserSections(DepartmentDTO departmentDTO) {
        try {
            User currentUser = utilityService.getCurrentLoggedInUser().orElseThrow(() -> new CustomException("User is not authorized.",HttpStatus.UNAUTHORIZED));
            List<UserRoleDepartment> userRoleDepartments = currentUser.getUserRoleDepartments().stream()
                    .filter(urd -> List.of(SystemRole.SUPER_ADMIN, "SUBDEPT_ADMIN", "DEPT_ADMIN").contains(urd.getRole().getRoleCode()))
                    .toList();
            
            System.out.println("All USER Roles: " + userRoleDepartments);
            // Use permission-based scope filtering (null = global access, empty = no access)
            List<Long> allowedSectionIds = permissionService.getAllowedSectionIds(currentUser.getId());
            List<Department> sections;
            if (userRoleDepartments.isEmpty()) {
                throw new CustomException("You have no roles", HttpStatus.BAD_REQUEST);
            } else {

                if(allowedSectionIds != null && allowedSectionIds.isEmpty()){
                    // Empty list means NO access (null means global access)
                    throw new CustomException("You have no permissions assigned. Please contact your administrator.", HttpStatus.FORBIDDEN);
                }else if(allowedSectionIds == null){
                    // Global access - get all sections, optionally filtered by requested departments
                    if (departmentDTO.getDepartmentIds() != null && !departmentDTO.getDepartmentIds().isEmpty()) {
                        sections = departmentRepository.findByDepartmentId_IdIn(departmentDTO.getDepartmentIds());
                    } else {
                        sections = departmentRepository.findByDepartmentIdIsNotNull();
                    }
                }else{
                    // Filter by allowed scope
                    if (departmentDTO.getDepartmentIds() != null && !departmentDTO.getDepartmentIds().isEmpty()) {
                        // Find sections that are both in allowed scope AND in requested departments
                        System.out.println("SQL: SELECT * FROM department WHERE id IN " + allowedSectionIds +
                                " AND department_id IN " + departmentDTO.getDepartmentIds());
                        sections = departmentRepository.findByIdInAndDepartmentId_IdIn(allowedSectionIds, departmentDTO.getDepartmentIds());
                    } else {
                        System.out.println("SQL: SELECT * FROM department WHERE id IN " + allowedSectionIds +
                                " AND department_id IS NOT NULL");
                        sections = departmentRepository.findByIdInAndDepartmentIdIsNotNull(allowedSectionIds);
                    }
                }
            }
            List<DepartmentDTO> departmentDTOS = sections.stream().map(dep -> utilityService.setDepartmentDTO(dep)).toList();
            return new ResponseDTO<>(true, "All Sections have been fetched", departmentDTOS);
        } catch (Exception e) {
            e.printStackTrace();
            return new ResponseDTO<>(false, "Something went wrong :" + e.getMessage());
        }
    }

    @Override
    public ResponseDTO<?> getDeptAdminDepartments() {
        try {
            User currentUser = utilityService.getCurrentLoggedInUser().orElseThrow(() -> new CustomException("User is unauthorized.",HttpStatus.UNAUTHORIZED));
            List<UserRoleDepartment> userRoleDepartments = currentUser.getUserRoleDepartments();
            userRoleDepartments = userRoleDepartments.stream()
                    .filter(urd->urd.getRole().getRoleCode().equals("DEPT_ADMIN"))
                    .toList();
            List<Department> departments;
            if (userRoleDepartments.isEmpty()){
                throw new CustomException("You have no Department Admin roles");
            }else{
                List<Long> departmentIds = userRoleDepartments.stream().map(urd -> urd.getDepartmentId().getId()).collect(Collectors.toList());
                departments = departmentRepository.findByIdIn(departmentIds);
            }
            List<DepartmentDTO> departmentDTOS = departments.stream().map(dep -> utilityService.setDepartmentDTO(dep)).toList();
            return new ResponseDTO<>(true, "All Departments have been fetched", departmentDTOS);
        } catch(Exception e) {
            e.printStackTrace();
            return new ResponseDTO<>(false, "Something went wrong :" + e.getMessage ());
        }
    }
    @Override
    public ResponseDTO<?> getSections(DepartmentDTO departmentDTO) {
        try {
            if (departmentDTO.getDepartmentIds() == null || departmentDTO.getDepartmentIds().isEmpty()){
                throw new CustomException("There is no any Department Id");
            }
            
            // 1. Identify Valid Scope (Department/Sections) based on input and logged-in user permissions
            List<Department> validDepartments = new ArrayList<>();
            User currentUser = utilityService.getCurrentLoggedInUser().orElseThrow(() -> new CustomException("User is not authorized.",HttpStatus.UNAUTHORIZED));
            List<UserRoleDepartment> userRoleDepartments = currentUser.getUserRoleDepartments().stream().filter(urd -> List.of(SystemRole.SUPER_ADMIN, "SUBDEPT_ADMIN", "DEPT_ADMIN").contains(urd.getRole().getRoleCode())).toList();
            // Use permission-based scope filtering (null = global access, empty = no access)
            List<Long> allowedDepartmentIds = permissionService.getAllowedDepartmentIds(currentUser.getId());
            
            if (userRoleDepartments.isEmpty()){
                throw new CustomException("You have no roles");
            } else if(allowedDepartmentIds != null && allowedDepartmentIds.isEmpty()){
                // Empty list means NO access (null means global access)
                throw new CustomException("You have no permissions assigned. Please contact your administrator.", HttpStatus.FORBIDDEN);
            } else if(allowedDepartmentIds == null){
                // Global access - get all sections for requested departments
                List<Long> requestedDeptIds = departmentDTO.getDepartmentIds();
                validDepartments = departmentRepository.findByDepartmentId_IdIn(requestedDeptIds);
            } else {
                // Filter by allowed scope
                List<Long> requestedDeptIds = departmentDTO.getDepartmentIds();
                List<Long> allowedRequestedDepts = requestedDeptIds.stream()
                        .filter(allowedDepartmentIds::contains)
                        .collect(Collectors.toList());
                
                if (!allowedRequestedDepts.isEmpty()) {
                    validDepartments = departmentRepository.findByDepartmentId_IdIn(allowedRequestedDepts);
                }
            }
            
            if (validDepartments.isEmpty()) {
                 return new ResponseDTO<>(true, "No sections found", new ArrayList<>());
            }

            // 2. Fetch Users associated with these Valid Departments/Sections
            List<Long> departmentIds = validDepartments.stream().map(Department::getId).collect(Collectors.toList());
            List<UserRoleDepartment> relatedUserRoles = userRoleDepartmentRepository.findByDepartmentId_IdIn(departmentIds);


            // 3. Transform to Requested JSON Structure
            // Group by User to create unique user entries
            Map<Long, List<UserRoleDepartment>> groupedByUser = relatedUserRoles.stream()
                    .filter(urd -> urd.getUser() != null)
                    .collect(Collectors.groupingBy(urd -> urd.getUser().getId()));

            List<Map<String, Object>> responseList = new ArrayList<>();

            for (Map.Entry<Long, List<UserRoleDepartment>> entry : groupedByUser.entrySet()) {
                User user = entry.getValue().get(0).getUser(); // Get user from first entry
                Map<String, Object> userMap = new LinkedHashMap<>();
                userMap.put("userid", user.getId());
                userMap.put("username", user.getUsername());
                userMap.put("email", user.getEmail());

                List<Map<String, Object>> departmentsList = new ArrayList<>();
                List<Map<String, Object>> sectionsList = new ArrayList<>();
                List<Map<String, Object>> rolesList = new ArrayList<>();
                List<String> checksheetList = new ArrayList<>(); 

                Set<Long> processedDeptIds = new HashSet<>();
                Set<Long> processedSectionIds = new HashSet<>();
                Set<Long> processedRoleIds = new HashSet<>();

                for (UserRoleDepartment urd : entry.getValue()) {
                    // Populate Departments/Sections
                    if (urd.getDepartmentId() != null) {
                        Department dept = urd.getDepartmentId();
                        if (dept.getDepartmentId() == null) {
                            // It is a Main Department
                            if (processedDeptIds.add(dept.getId())) {
                                Map<String, Object> dMap = new HashMap<>();
                                dMap.put("departmentid", dept.getId());
                                dMap.put("department_name", dept.getName());
                                departmentsList.add(dMap);
                            }
                        } else {
                            // It is a Section (Sub-Department)
                            if (processedSectionIds.add(dept.getId())) {
                                Map<String, Object> sMap = new HashMap<>();
                                sMap.put("sectionid", dept.getId());
                                sMap.put("section_name", dept.getName());
                                sectionsList.add(sMap);
                            }
                            // Also add parent department if needed? JSON example distinguishes "departments" and "sections".
                            // Usually a section implies a parent department. 
                            if (processedDeptIds.add(dept.getDepartmentId().getId())) {
                                Map<String, Object> dMap = new HashMap<>();
                                dMap.put("departmentid", dept.getDepartmentId().getId());
                                dMap.put("department_name", dept.getDepartmentId().getName());
                                departmentsList.add(dMap);
                            }
                        }
                    }

                    // Populate Roles
                    if (urd.getRole() != null && processedRoleIds.add(urd.getRole().getId())) {
                        Map<String, Object> rMap = new HashMap<>();
                        rMap.put("role_id", urd.getRole().getId());
                        rMap.put("role_name", urd.getRole().getName()); // or RoleCode? Example says "role_name": "department_head"
                        rolesList.add(rMap);
                    }
                    
                    // Fetch Checksheets (as per text request "Roles & Checksheet")
                    // Note: This might be expensive in a loop.
                     String roleCode = urd.getRole().getRoleCode();
                     List<String> cs = null;
                    if(Objects.equals(roleCode, "OPERATOR")) {
                        cs = checksheetDAO.getChecksheetNames(user.getId(), "operator_user_ids", null);
                    } else if(Objects.equals(roleCode, "SUBDEPT_ADMIN")) {
                        // SUBDEPT_ADMIN handles validator and approver checksheets
                        cs = checksheetDAO.getChecksheetNames(user.getId(), "validator_user_ids", null);
                        if (cs != null) checksheetList.addAll(cs);
                        cs = checksheetDAO.getChecksheetNames(user.getId(), "approver_user_ids", null);
                    } else if(Objects.equals(roleCode, "DEPT_ADMIN")) {
                        // DEPT_ADMIN handles data validator and data approver checksheets
                        cs = checksheetDAO.getChecksheetNames(user.getId(), "data_validator_user_ids", null);
                        if (cs != null) checksheetList.addAll(cs);
                        cs = checksheetDAO.getChecksheetNames(user.getId(), "data_approver_user_ids", null);
                    }
                    if (cs != null) {
                        checksheetList.addAll(cs);
                    }
                }

                userMap.put("departments", departmentsList);
                userMap.put("sections", sectionsList);
                userMap.put("roles", rolesList);
                userMap.put("checksheets", checksheetList.stream().distinct().toList());
                
                responseList.add(userMap);
            }

            return new ResponseDTO<>(true, "Users with details fetched successfully", responseList);
            
        } catch(Exception e) {
            e.printStackTrace();
            return new ResponseDTO<>(false, "Something went wrong :" + e.getMessage ());
        }
    }

    @Override
    public ResponseDTO<?> getUserRoleDetails(Long userId) throws CustomException {
        try {
            if (userId == null) {
                return new ResponseDTO<>(false, "Please provide userId");
            }

            Optional<User> userOpt = userRepository.findById(userId);
            if (userOpt.isEmpty()) {
                return new ResponseDTO<>(false, "User not found");
            }
            User user = userOpt.get();

            List<UserRoleDepartment> userRoleByUserIdDepartment = userRoleDepartmentRepository.findByUser_IdAndDeletedByIsNull(userId);
            if (Objects.equals(userRoleByUserIdDepartment, null) || userRoleByUserIdDepartment.isEmpty()) {
                // If no role/dept assigned, still return basic user info? 
                // Matches logic in searchUsers which throws exception for invalid role, but here we might want to be graceful.
                // For consisteny with searchUsers validation:
                throw new CustomException("User has no assigned role/department", HttpStatus.UNPROCESSABLE_ENTITY);
            }
            
            // Construct DepartmentDTO (which seems to doubles as a User Detail DTO in this context)
            DepartmentDTO userDetailDTO = new DepartmentDTO();
            userDetailDTO.setUserId(user.getId());
            userDetailDTO.setFirstName(user.getFirstName());
            userDetailDTO.setLastName(user.getLastName());
            userDetailDTO.setEmail(user.getEmail());
            userDetailDTO.setMobile(user.getMobile());
            userDetailDTO.setUsername(user.getUsername());
            
            // Populate Role/Dept info
            // Notes: searchUsers iterates list, but for single user view, we usually show the primary or list all. 
            // The searchUsers logic seems to flatten result per user. 
            // Here we can aggregate.
            
            // List<String> roles = userRoleByUserIdDepartment.stream()
            //        .map(urd -> urd.getRole().getRoleCode())
            //        .distinct()
            //        .collect(Collectors.toList());
            
            // Checksheets logic from searchUsers
            // It relies on role code to decide which checksheets to fetch.
            // Since a user can have multiple roles, we should probably fetch for all applicable roles.
            
            List<com.checkSheet.DTO.ChecksheetDTO> allChecksheetDetails = new ArrayList<>();
            List<RoleDTO> roleDTOs = new ArrayList<>();
            List<DepartmentDTO> departmentDTOs = new ArrayList<>();
            List<DepartmentDTO> sectionDTOs = new ArrayList<>();
            
            for (UserRoleDepartment urd : userRoleByUserIdDepartment) {
                // Roles
                if (urd.getRole() != null) {
                    RoleDTO roleDTO = new RoleDTO(urd.getRole().getId(), urd.getRole().getName(), urd.getRole().getRoleCode());
                    if (roleDTOs.stream().noneMatch(r -> r.getId().equals(roleDTO.getId()))) {
                        roleDTOs.add(roleDTO);
                    }
                }
                
                // Departments and Sections
                if (urd.getDepartmentId() != null) {
                    Department dept = urd.getDepartmentId();
                    if (dept.getDepartmentId() == null) {
                        // Main Department
                        if (departmentDTOs.stream().noneMatch(d -> d.getId().equals(dept.getId()))) {
                            departmentDTOs.add(new DepartmentDTO(dept.getId(), dept.getName(), null));
                        }
                    } else {
                        // Section
                        if (sectionDTOs.stream().noneMatch(s -> s.getId().equals(dept.getId()))) {
                            sectionDTOs.add(new DepartmentDTO(dept.getId(), dept.getName(), dept.getDepartmentId().getId()));
                        }
                    }
                }

                String roleCode = urd.getRole().getRoleCode();
                List<com.checkSheet.DTO.ChecksheetDTO> checksheets = new ArrayList<>();
                String currentRoleName = roleCode; // Default to code or map to friendly name if needed
                
                // Fetch checksheets based on role
                if(Objects.equals(roleCode, "OPERATOR")) {
                    checksheets = checksheetDAO.getChecksheetDetails(userId, "operator_user_ids", null);
                } else if(Objects.equals(roleCode, "SUBDEPT_ADMIN")) {
                    // SUBDEPT_ADMIN handles validator and approver checksheets
                    checksheets = checksheetDAO.getChecksheetDetails(userId, "validator_user_ids", null);
                    if (checksheets != null) {
                        for(com.checkSheet.DTO.ChecksheetDTO cs : checksheets) {
                            cs.setUserRole(currentRoleName);
                        }
                        allChecksheetDetails.addAll(checksheets);
                    }
                    checksheets = checksheetDAO.getChecksheetDetails(userId, "approver_user_ids", null);
                } else if(Objects.equals(roleCode, "DEPT_ADMIN")) {
                    // DEPT_ADMIN handles data validator and data approver checksheets
                    checksheets = checksheetDAO.getChecksheetDetails(userId, "data_validator_user_ids", null);
                    if (checksheets != null) {
                        for(com.checkSheet.DTO.ChecksheetDTO cs : checksheets) {
                            cs.setUserRole(currentRoleName);
                        }
                        allChecksheetDetails.addAll(checksheets);
                    }
                    checksheets = checksheetDAO.getChecksheetDetails(userId, "data_approver_user_ids", null);
                }
                
                if (checksheets != null) {
                    for(com.checkSheet.DTO.ChecksheetDTO cs : checksheets) {
                        cs.setUserRole(currentRoleName);
                    }
                    allChecksheetDetails.addAll(checksheets);
                }
            }
            
            userDetailDTO.setChecksheetDetails(allChecksheetDetails.stream().distinct().collect(Collectors.toList()));
            // userDetailDTO.setRoleCode(String.join(", ", roles)); // Or handle as list if DTO supports it
             
            userDetailDTO.setRoles(roleDTOs);
            userDetailDTO.setDepartments(departmentDTOs);
            userDetailDTO.setSections(sectionDTOs);

            // Map Departments/Sections
            //  List<String> departmentNames = userRoleByUserIdDepartment.stream()
            //         .map(urd -> urd.getDepartmentId() != null ? urd.getDepartmentId().getName() : "")
            //         .filter(name -> !name.isEmpty())
            //         .distinct()
            //         .collect(Collectors.toList());
            // userDetailDTO.setDepartmentName(String.join(", ", departmentNames));


            return new ResponseDTO<>(true, "User details fetched successfully", userDetailDTO);

        } catch (Exception e) {
            e.printStackTrace();
            return new ResponseDTO<>(false, e.getMessage());
        }
    }

    @Override
    public ResponseDTO<?> getUserRoleDepartment(Long userRoleDepartmentId) throws CustomException {
        try{
            Optional<UserRoleDepartment> urdOpt = userRoleDepartmentRepository.findById(userRoleDepartmentId);
            if(urdOpt.isPresent()){
                UserRoleDepartment urd = urdOpt.get();
                Role role = urd.getRole();
                User usr = urd.getUser();
                Department dept = urd.getDepartmentId();
                DepartmentDTO deptDto = new DepartmentDTO();
                deptDto.setId(userRoleDepartmentId);
                deptDto.setUsername(usr.getUsername());
                if("DEPT_ADMIN".equals(role.getRoleCode())){
                    deptDto.setDepartmentId(dept.getId());
                }else if("SUBDEPT_ADMIN".equals(role.getRoleCode())){
                    deptDto.setSectiontId(dept.getId());
                    deptDto.setSectionName(dept.getName());
                    deptDto.setDepartmentId(dept.getDepartmentId().getId());
                }else if(!SystemRole.SUPER_ADMIN.equals(role.getRoleCode())) {
                    deptDto.setSectiontId(dept.getId());
                    deptDto.setSectionName(dept.getName());
                    deptDto.setDepartmentId(dept.getDepartmentId().getId());
                }
                deptDto.setRoleId(role.getId());
                deptDto.setUserId(usr.getId());
                deptDto.setFirstName(usr.getFirstName());
                deptDto.setLastName(usr.getLastName());
                deptDto.setEmail(usr.getEmail());
                deptDto.setMobile(usr.getMobile());
                return new ResponseDTO<>(true, "User Details are fetched successfully!!", deptDto);
            }else{
                throw new CustomException("Invalid User Role Department ID", HttpStatus.UNPROCESSABLE_ENTITY);
            }
        } catch(Exception e) {
            e.printStackTrace();
            return new ResponseDTO<>(false, "Something went wrong :" + e.getMessage ());
        }
    }
    // END OF FILE
}
