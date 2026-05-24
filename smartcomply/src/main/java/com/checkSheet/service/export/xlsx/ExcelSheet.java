package com.checkSheet.service.export.xlsx;

import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFFont;

import com.checkSheet.DTO.DepartmentDTO;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;

public class ExcelSheet {
    private SXSSFWorkbook workbook;
    private Sheet sheet;


    public ExcelSheet() {
        workbook = new SXSSFWorkbook();
    }

    private void writeHeaderForDepartments() {
        sheet = workbook.createSheet("Report");
        Row row = sheet.createRow(0);
        CellStyle style = workbook.createCellStyle();
        XSSFFont font = (XSSFFont) workbook.createFont();
        font.setBold(true);
        font.setFontHeight(11);
        style.setFont(font);
        createCell(row, 0, "No", style);
        createCell(row, 1, "Department name", style);
        createCell(row, 2, "Name", style);
        createCell(row, 3, "Email", style);
        createCell(row, 4, "Username", style);
    }

    private void writeHeaderForSections() {
        sheet = workbook.createSheet("Report");
        Row row = sheet.createRow(0);
        CellStyle style = workbook.createCellStyle();
        XSSFFont font = (XSSFFont) workbook.createFont();
        font.setBold(true);
        font.setFontHeight(11);
        style.setFont(font);
        createCell(row, 0, "No", style);
        createCell(row, 1, "Section name", style);
        createCell(row, 2, "Name", style);
        createCell(row, 3, "Email", style);
        createCell(row, 4, "Username", style);
    }

    private void writeHeaderForUsers() {
        sheet = workbook.createSheet("Report");
        Row row = sheet.createRow(0);
        CellStyle style = workbook.createCellStyle();
        XSSFFont font = (XSSFFont) workbook.createFont();
        font.setBold(true);
        font.setFontHeight(11);
        style.setFont(font);
        createCell(row, 0, "No", style);
        createCell(row, 1, "Role", style);
        createCell(row, 2, "Name", style);
        createCell(row, 3, "Email", style);
        createCell(row, 4, "Username", style);
    }

    private void createCell(Row row, int columnCount, Object valueOfCell, CellStyle style) {
//        sheet1.autoSizeColumn(columnCount);
        Cell cell = row.createCell(columnCount);

        if (valueOfCell instanceof Integer) {
            cell.setCellValue((Integer) valueOfCell);
        } else if (valueOfCell instanceof Long) {
            cell.setCellValue((Long) valueOfCell);
        } else if (valueOfCell instanceof String) {
            cell.setCellValue((String) valueOfCell);
        } else if (valueOfCell instanceof Date) {
            DateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");
            cell.setCellValue(df.format(valueOfCell));
        } else if(valueOfCell instanceof Boolean) {
            cell.setCellValue((Boolean) valueOfCell);
        } else if(Objects.equals(valueOfCell, null)) {
            cell.setCellValue("");
        }
        cell.setCellStyle(style);
    }

    public void generateExcelFileForDepartments(HttpServletResponse response, List<DepartmentDTO> loginForReport) throws IOException {
        writeHeaderForDepartments();
        writeForDepartments(loginForReport);
        ServletOutputStream outputStream = response.getOutputStream();
        workbook.write(outputStream);
        workbook.close();
        outputStream.close();
    }

    public void generateExcelFileForSection(HttpServletResponse response, List<DepartmentDTO> loginForReport) throws IOException {
        ServletOutputStream outputStream = null;
        try {
            writeHeaderForSections();
            writeForSections(loginForReport);
            
            outputStream = response.getOutputStream();
            workbook.write(outputStream);
            outputStream.flush();
            
        } catch (IOException e) {
            throw new IOException("Error generating excel file: " + e.getMessage(), e);
        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    // log error
                }
            }
            if (workbook != null) {
                try {
                    workbook.close();
                } catch (IOException e) {
                    // log error
                }
            }
        }
    }

    public void generateExcelFileForUsers(HttpServletResponse response, List<DepartmentDTO> loginForReport) throws IOException {
        writeHeaderForUsers();
        writeForUsers(loginForReport);
        ServletOutputStream outputStream = response.getOutputStream();
        workbook.write(outputStream);
        workbook.close();
        outputStream.close();
    }

    private void writeForDepartments(List<DepartmentDTO> departmentDTOList) {
        Integer rowCount = 1;
        CellStyle style = workbook.createCellStyle();
        XSSFFont font = (XSSFFont) workbook.createFont();
        font.setFontHeight(11);
        style.setFont(font);

        for(int i = 0, bookingForReportSize = departmentDTOList.size(); i < bookingForReportSize; i++) {
            DepartmentDTO departmentDTO = departmentDTOList.get(i);
            Row row = sheet.createRow(rowCount++);
            Integer columnCount = 0;

            createCell(row, columnCount++, (i + 1), style);
            createCell(row, columnCount++, departmentDTO.getName(), style);
            createCell(row, columnCount++, (departmentDTO.getFirstName() == null ? "" : departmentDTO.getFirstName()) + " " +
                    (departmentDTO.getLastName() == null ? "" : departmentDTO.getLastName()), style);
            createCell(row, columnCount++, departmentDTO.getEmail(), style);
            createCell(row, columnCount++, departmentDTO.getUsername(), style);
        }
    }

    private void writeForSections(List<DepartmentDTO> departmentDTOList) {
        Integer rowCount = 1;
        CellStyle style = workbook.createCellStyle();
        XSSFFont font = (XSSFFont) workbook.createFont();
        font.setFontHeight(11);
        style.setFont(font);

        for(int i = 0, bookingForReportSize = departmentDTOList.size(); i < bookingForReportSize; i++) {
            DepartmentDTO departmentDTO = departmentDTOList.get(i);
            Row row = sheet.createRow(rowCount++);
            Integer columnCount = 0;

            createCell(row, columnCount++, (i + 1), style);
            createCell(row, columnCount++, departmentDTO.getName(), style);
            createCell(row, columnCount++, (departmentDTO.getFirstName() == null ? "" : departmentDTO.getFirstName()) + " " +
                    (departmentDTO.getLastName() == null ? "" : departmentDTO.getLastName()), style);
            createCell(row, columnCount++, departmentDTO.getEmail(), style);
            createCell(row, columnCount++, departmentDTO.getUsername(), style);
        }
    }

    private void writeForUsers(List<DepartmentDTO> departmentDTOList) {
        Integer rowCount = 1;
        CellStyle style = workbook.createCellStyle();
        XSSFFont font = (XSSFFont) workbook.createFont();
        font.setFontHeight(11);
        style.setFont(font);

        for(int i = 0, bookingForReportSize = departmentDTOList.size(); i < bookingForReportSize; i++) {
            DepartmentDTO departmentDTO = departmentDTOList.get(i);
            Row row = sheet.createRow(rowCount++);
            Integer columnCount = 0;

            createCell(row, columnCount++, (i + 1), style);
            createCell(row, columnCount++, departmentDTO.getRoleName(), style);
            createCell(row, columnCount++, (departmentDTO.getFirstName() == null ? "" : departmentDTO.getFirstName()) + " " +
                    (departmentDTO.getLastName() == null ? "" : departmentDTO.getLastName()), style);
            createCell(row, columnCount++, departmentDTO.getEmail(), style);
            createCell(row, columnCount++, departmentDTO.getUsername(), style);
        }
    }
}
