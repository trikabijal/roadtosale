package com.checkSheet.service;

import com.checkSheet.DTO.response.ResponseDTO;
import com.checkSheet.entity.AuditeeType;
import com.checkSheet.exception.CustomException;
import com.checkSheet.repository.AuditeeTypeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuditeeTypeServiceImpl implements AuditeeTypeService {

    @Autowired
    private AuditeeTypeRepository auditeeTypeRepository;

    @Override
    public ResponseDTO<?> getAuditeeTypes() throws CustomException {
        try {
            List<AuditeeType> auditeeTypes = auditeeTypeRepository.findAllByOrderByLabelAsc();
            return new ResponseDTO<>("Auditee types fetched successfully", auditeeTypes);
        } catch (Exception e) {
            e.printStackTrace();
            throw new CustomException("Error fetching auditee types", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
