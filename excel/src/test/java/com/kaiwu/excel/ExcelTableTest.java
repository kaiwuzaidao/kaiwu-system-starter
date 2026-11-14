package com.kaiwu.excel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/** 验证模板契约、资源上限和不可信表格输入。 */
class ExcelTableTest {
    /** 往返保持文本与行号，包括以等号开头的文本。 */
    @Test
    void roundTripAndPreserveRows() throws Exception {
        var out = new ByteArrayOutputStream();
        ExcelTable.write(out, List.of("编码", "名称"), List.of(List.of("001", "=1+2")), 5);
        var rows = ExcelTable.read(new ByteArrayInputStream(out.toByteArray()), List.of("编码", "名称"), 100000, 5);
        assertEquals(2, rows.getFirst().rowNumber());
        assertEquals(List.of("001", "=1+2"), rows.getFirst().cells());
    }

    /** 错误表头与文件、导出行数上限必须拒绝。 */
    @Test
    void rejectWrongHeadersAndLimits() throws Exception {
        byte[] bytes = ExcelTable.template(List.of("编码"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ExcelTable.read(new ByteArrayInputStream(bytes), List.of("名称"), 100000, 5));
        assertThrows(
                IllegalArgumentException.class,
                () -> ExcelTable.read(new ByteArrayInputStream(bytes), List.of("编码"), 10, 5));
        assertThrows(
                IllegalArgumentException.class,
                () -> ExcelTable.write(
                        new ByteArrayOutputStream(), List.of("编码"), List.of(List.of("a"), List.of("b")), 1));
    }

    /** 逐行失败保留行号，意外异常向上传播。 */
    @Test
    void businessFailuresKeepRowNumbersAndUnexpectedErrorsPropagate() {
        var rows = List.of(new ExcelTable.RowData(2, List.of("ok")), new ExcelTable.RowData(4, List.of("bad")));
        var result = ExcelTable.process(rows, row -> row.cells().getFirst().equals("bad") ? "编码无效" : null);
        assertEquals(1, result.successCount());
        assertEquals(new ExcelTable.RowError(4, "编码无效"), result.errors().getFirst());
        assertThrows(
                IllegalStateException.class,
                () -> ExcelTable.process(rows, row -> {
                    throw new IllegalStateException();
                }));
    }

    /** 额外列和超限行不得导入。 */
    @Test
    void rejectExtraColumnsAndTooManyRows() throws Exception {
        try (var workbook = new XSSFWorkbook();
                var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("编码");
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("A");
            row.createCell(1).setCellValue("extra");
            workbook.write(out);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ExcelTable.read(new ByteArrayInputStream(out.toByteArray()), List.of("编码"), 100000, 5));
        }
        try (var workbook = new XSSFWorkbook();
                var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("编码");
            sheet.createRow(2).createCell(0).setCellValue("A");
            workbook.write(out);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ExcelTable.read(new ByteArrayInputStream(out.toByteArray()), List.of("编码"), 100000, 1));
        }
    }

    /** 公式单元格不得导入。 */
    @Test
    void rejectFormulaAndExtraColumn() throws Exception {
        try (var workbook = new XSSFWorkbook();
                var out = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet();
            sheet.createRow(0).createCell(0).setCellValue("编码");
            sheet.createRow(1).createCell(0).setCellFormula("1+2");
            workbook.write(out);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ExcelTable.read(new ByteArrayInputStream(out.toByteArray()), List.of("编码"), 100000, 5));
        }
    }
    /** 模板与业务数据必须保留有意义的空格。 */
    @Test
    void preservesHeaderAndDataWhitespace() throws Exception {
        var output = new ByteArrayOutputStream();
        ExcelTable.write(output, List.of(" 名称 "), List.of(List.of("  A  ")), 2);
        var result = ExcelTable.read(new ByteArrayInputStream(output.toByteArray()), List.of(" 名称 "), 100000, 2);
        assertEquals("  A  ", result.getFirst().cells().getFirst());
    }

    /** 展开量超限时，在工作簿物化之前拒绝文件。 */
    @Test
    void rejectsExcessiveExpandedContent() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("large.xml"));
            byte[] chunk = new byte[8192];
            for (int i = 0; i < 2049; i++) {
                zip.write(chunk);
            }
            zip.closeEntry();
        }
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> ExcelTable.read(new ByteArrayInputStream(output.toByteArray()), List.of("名称"), 1000000, 1));
        assertEquals("文件展开后超过大小限制", failure.getMessage());
    }
    /** 压缩包条目超过限制时不再创建工作簿对象。 */
    @Test
    void rejectsTooManyZipEntries() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            for (int i = 0; i < 257; i++) {
                zip.putNextEntry(new ZipEntry("item" + i));
                zip.closeEntry();
            }
        }
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> ExcelTable.read(new ByteArrayInputStream(output.toByteArray()), List.of("名称"), 100000, 1));
        assertEquals("文件包含过多压缩条目或跨磁盘数据", failure.getMessage());
    }
    /** 中央目录伪造较小条目数时必须拒绝，防止绕过数量限制。 */
    @Test
    void rejectsForgedDirectoryEntryCount() throws Exception {
        byte[] bytes = ExcelTable.template(List.of("名称"));
        int eocd = bytes.length - 22;
        bytes[eocd + 8] = 1;
        bytes[eocd + 9] = 0;
        bytes[eocd + 10] = 1;
        bytes[eocd + 11] = 0;
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> ExcelTable.read(new ByteArrayInputStream(bytes), List.of("名称"), 100000, 2));
        assertEquals("XLSX 中央目录计数不一致", failure.getMessage());
    }
}
