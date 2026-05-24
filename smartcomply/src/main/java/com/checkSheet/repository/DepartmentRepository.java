package com.checkSheet.repository;

import com.checkSheet.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
@Repository
public interface DepartmentRepository extends JpaRepository<Department, Long> {

    @Override
    Optional<Department> findById(Long id);
    Optional<Department> findByNameAndIdNot(String name, Long id);

    Optional<Department> findByName(String name);

    //Get Departments or Sections by id
    List<Department> findByIdIn(List<Long> ids);
    //Get Sections of given department Id
    List<Department> findByDepartmentId_Id(Long departmentId);

    //Get Sections/SubDepartment of given department Ids
    List<Department> findByDepartmentId_IdIn(List<Long> departmentId);

    Optional<Department> findByIdAndDepartmentIdIsNull(Long id);

    Optional<Department> findByDepartmentIdAndName(Department department, String name);

    List<Department> findByDepartmentIdIsNull();

    List<Department> findByDepartmentIdIsNotNull();

    List<Department> findByIdInAndDepartmentIdIsNull(List<Long> departmentIds);

    List<Department> findByIdInAndDepartmentIdIsNotNull(List<Long> departmentIds);

    List<Department> findByIdInAndDepartmentId_IdIn(List<Long> departmentIds, List<Long> departmentIds1);

}
