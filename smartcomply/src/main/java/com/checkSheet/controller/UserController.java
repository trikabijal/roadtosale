package com.checkSheet.controller;

import com.checkSheet.DTO.UserDTO;
import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.exception.CustomException;
import com.checkSheet.service.DepartmentService;
import com.checkSheet.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "*", allowedHeaders = "*")
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

//    public UserController(UserService userService) {
//        this.userService = userService;
//    }

    @Autowired
    private DepartmentService departmentService;


    @GetMapping("/hello")
    public String hello() {
        System.out.println("hello : ");
        return "Hello world";
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.register(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/createOrEditOperator")
    public ResponseEntity<?> createOrEditOperator(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.createOrEditOperator(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/deleteUser")
    public ResponseEntity<?> deleteUser(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.deleteUser(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getOperator")
    public ResponseEntity<?> getOperator(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.getOperator(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getAllOperators")
    public ResponseEntity<?> getAllOperators(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.getAllOperators(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getChecksheetPreparers")
    public ResponseEntity<?> getChecksheetPreparers(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.getChecksheetPreparers(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getChecksheetValidators")
    public ResponseEntity<?> getChecksheetValidators(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.getChecksheetValidators(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getChecksheetApprovers")
    public ResponseEntity<?> getChecksheetApprovers(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.getChecksheetApprovers(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getDataValidators")
    public ResponseEntity<?> getDataValidators(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.getDataValidators(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getDataApprovers")
    public ResponseEntity<?> getDataApprovers(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.getDataApprovers(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getChecksheetOperators")
    public ResponseEntity<?> getChecksheetOperators(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.getChecksheetOperators(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getAlertUsers")
    public ResponseEntity<?> getAlertUsers(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.getAlertUsers(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/getEscalationUsers")
    public ResponseEntity<?> getEscalationUsers(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.getEscalationUsers(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> authenticate(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.login(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }


    @PostMapping("/validateAndSaveUsername")
    public ResponseEntity<?> validateAndSaveUsername(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.validateAndSaveUsername(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/refreshToken")
    public ResponseEntity<?> refreshToken(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.refreshToken(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/updatePassword")
    public ResponseEntity<?> updatePassword(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.updatePassword(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    @PostMapping("/resetPassword")
    public ResponseEntity<?> resetPassword(@RequestBody UserDTO userDTO) {
        try {
            return ResponseEntity.ok(userService.resetPassword(userDTO));
        } catch (CustomException e) {
            return ResponseEntity
                    .status(e.getHttpStatus())
                    .body(new ResponseDTO<>(false, e.getMessage()));
        }
    }

    /**
     * Gets the logged in user and role response
     *
     * @param ipn
     * @param pwd
     * @return
     */
    @GetMapping
    public ResponseEntity<Object> login(@RequestParam(value = "ipn", required = true) String ipn,
                                        @RequestParam(value = "pwd", required = true) String pwd) {
        try {
            return new ResponseEntity<>(userService.findUserByIpn(ipn, pwd), HttpStatus.OK);

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity
                    .status(404)
                    .body(new ResponseDTO<>(false, e.getMessage()));

        }

    }
}
