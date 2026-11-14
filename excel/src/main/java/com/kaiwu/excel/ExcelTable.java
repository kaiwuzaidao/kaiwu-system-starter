package com.kaiwu.excel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

/** 数据库无关的单工作表 Excel 编解码；授权、字段含义及业务写入由调用方负责。 */
public final class ExcelTable {
    /** 组件只提供静态方法，不维护跨请求状态。 */
    private ExcelTable() {}

    private static final long MAX_UNCOMPRESSED_BYTES = 16L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 256;

    /**
     * 一条解析结果保留原始行号，便于业务校验向用户定位错误。
     * @param rowNumber 工作表中的一基行号
     * @param cells 按表头顺序排列的文本
     */
    public record RowData(int rowNumber, List<String> cells) {}

    /**
     * 业务校验或保存产生的安全错误；不包含原始单元格内容。
     * @param rowNumber 工作表中的一基行号
     * @param message 可安全展示的错误信息
     */
    public record RowError(int rowNumber, String message) {}

    /**
     * 汇总业务行处理结果；调用方选择事务边界并确保错误说明可安全展示。
     * @param successCount 处理器返回成功的行数
     * @param errors 按工作表顺序排列的失败信息
     */
    public record ImportResult(int successCount, List<RowError> errors) {}

    /** 返回空字符串或 null 表示成功；其他内容是可展示的校验错误。 */
    @FunctionalInterface
    public interface RowHandler {
        /**
         * 根据业务规则校验并处理单行；意外异常向调用方传播。
         * @param row 带工作表行号的文本值
         * @return 可展示的错误信息，成功时返回 null 或空文本
         */
        String handle(RowData row);
    }

    /**
     * 按顺序调用业务处理器；不吞掉意外异常，也不决定事务提交策略。
     * @param rows 已解析的行，不含表头
     * @param handler 业务校验及保存处理器
     * @return 成功数量与带原始行号的安全错误
     */
    public static ImportResult process(List<RowData> rows, RowHandler handler) {
        int successes = 0;
        List<RowError> errors = new ArrayList<>();
        for (RowData row : rows) {
            String error = handler.handle(row);
            if (error == null || error.isBlank()) {
                successes++;
            } else {
                errors.add(new RowError(row.rowNumber(), error));
            }
        }
        return new ImportResult(successes, List.copyOf(errors));
    }

    /**
     * 读取首张工作表，严格校验表头及文件和行数上限；拒绝公式以免接受不可信计算结果。
     * 调用方应先完成权限验证，并在业务层逐行校验和写入。
     * @param input 未加密 XLSX 输入流，由调用者关闭
     * @param headers 精确表头与顺序
     * @param maxBytes 压缩文件的最大字节数
     * @param maxRows 除表头外允许的最大行号
     * @return 保留原始行号的非空数据行
     * @throws IOException 文件读取失败或压缩包损坏
     */
    public static List<RowData> read(InputStream input, List<String> headers, int maxBytes, int maxRows)
            throws IOException {
        validate(headers, maxBytes, maxRows);
        byte[] bytes = input.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("文件超过大小限制");
        }
        checkArchive(bytes);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() != 1) {
                throw new IllegalArgumentException("文件必须包含一个工作表");
            }
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet.getLastRowNum() > maxRows) {
                throw new IllegalArgumentException("文件超过行数限制");
            }
            DataFormatter formatter = new DataFormatter();
            Row header = sheet.getRow(0);
            if (header == null || header.getLastCellNum() != headers.size()) {
                throw new IllegalArgumentException("表头不匹配");
            }
            for (int i = 0; i < headers.size(); i++) {
                if (!headers.get(i).equals(value(header.getCell(i), formatter))) {
                    throw new IllegalArgumentException("表头不匹配");
                }
            }
            List<RowData> rows = new ArrayList<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                if (row.getLastCellNum() > headers.size()) {
                    throw new IllegalArgumentException("第 " + (i + 1) + " 行包含额外列");
                }
                List<String> cells = new ArrayList<>();
                for (int col = 0; col < headers.size(); col++) {
                    cells.add(value(row.getCell(col), formatter));
                }
                if (cells.stream().anyMatch(s -> !s.isBlank())) {
                    rows.add(new RowData(i + 1, List.copyOf(cells)));
                }
            }
            return List.copyOf(rows);
        } catch (org.apache.poi.EncryptedDocumentException e) {
            throw new IllegalArgumentException("不支持加密文件", e);
        }
    }

    /**
     * 在 POI 建立工作簿对象前限制 XLSX 压缩包的条目数和累计展开字节。
     * 只支持 XLSX，防止旧格式绕过 ZIP 资源上限；不会提取文件到磁盘。
     */
    private static void checkArchive(byte[] bytes) throws IOException {
        if (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K' || bytes[2] != 3 || bytes[3] != 4) {
            throw new IllegalArgumentException("仅支持未加密的 XLSX 文件");
        }
        checkDirectorySize(bytes);
        int entries = 0;
        long total = 0;
        byte[] buffer = new byte[8192];
        try (var channel = new SeekableInMemoryByteChannel(bytes);
                var zip = ZipFile.builder().setSeekableByteChannel(channel).get()) {
            Enumeration<ZipArchiveEntry> files = zip.getEntries();
            while (files.hasMoreElements()) {
                ZipArchiveEntry entry = files.nextElement();
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw new IllegalArgumentException("文件包含过多压缩条目");
                }
                try (InputStream entryStream = zip.getInputStream(entry)) {
                    int count;
                    while ((count = entryStream.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_UNCOMPRESSED_BYTES) {
                            throw new IllegalArgumentException("文件展开后超过大小限制");
                        }
                    }
                }
            }
        }
        if (entries == 0) {
            throw new IllegalArgumentException("无效的 XLSX 文件");
        }
    }

    /**
     * 在压缩库加载中央目录前校验条目数，避免大量条目的对象分配。
     * 常规 XLSX 的 ZIP64 附加字段可保留；超出常规目录计数范围的文件不接收。
     */
    private static void checkDirectorySize(byte[] bytes) {
        int first = Math.max(0, bytes.length - 65557);
        for (int offset = bytes.length - 22; offset >= first; offset--) {
            if (bytes[offset] == 0x50
                    && bytes[offset + 1] == 0x4b
                    && bytes[offset + 2] == 0x05
                    && bytes[offset + 3] == 0x06
                    && offset + 22 + unsignedShort(bytes, offset + 20) == bytes.length) {
                int disks = unsignedShort(bytes, offset + 4) | unsignedShort(bytes, offset + 6);
                int diskEntries = unsignedShort(bytes, offset + 8);
                int entries = unsignedShort(bytes, offset + 10);
                if (disks != 0 || diskEntries != entries || entries > MAX_ZIP_ENTRIES) {
                    throw new IllegalArgumentException("文件包含过多压缩条目或跨磁盘数据");
                }
                long start = unsignedInt(bytes, offset + 16);
                long size = unsignedInt(bytes, offset + 12);
                if (start + size > offset || start + size > bytes.length) {
                    throw new IllegalArgumentException("无效的 XLSX 中央目录");
                }
                int cursor = (int) start;
                int end = (int) (start + size);
                int actual = 0;
                while (cursor < end) {
                    if (cursor + 46 > end || unsignedInt(bytes, cursor) != 0x02014b50L) {
                        throw new IllegalArgumentException("无效的 XLSX 中央目录");
                    }
                    int length = 46
                            + unsignedShort(bytes, cursor + 28)
                            + unsignedShort(bytes, cursor + 30)
                            + unsignedShort(bytes, cursor + 32);
                    if (++actual > MAX_ZIP_ENTRIES || cursor + length > end) {
                        throw new IllegalArgumentException("文件包含过多压缩条目或跨磁盘数据");
                    }
                    cursor += length;
                }
                if (actual != entries) {
                    throw new IllegalArgumentException("XLSX 中央目录计数不一致");
                }
                return;
            }
        }
        throw new IllegalArgumentException("无效的 XLSX 文件");
    }

    /** 读取 ZIP 中的小端无符号短整数。 */
    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    /** 读取 ZIP 中的小端无符号 32 位整数。 */
    private static long unsignedInt(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(unsignedShort(bytes, offset) | (unsignedShort(bytes, offset + 2) << 16));
    }

    /**
     * 使用滑动窗口写出表格；调用方必须使用受权限和行数限制的查询结果。
     * @param output 输出流，由调用者关闭
     * @param headers 精确表头与顺序
     * @param rows 已按权限筛选的文本行
     * @param maxRows 最大数据行数，超限时不写入输出流
     * @throws IOException 生成或写出失败
     */
    public static void write(
            OutputStream output, List<String> headers, Iterable<? extends List<String>> rows, int maxRows)
            throws IOException {
        validate(headers, 1, maxRows);
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            Sheet sheet = workbook.createSheet("data");
            writeRow(sheet.createRow(0), headers);
            int count = 0;
            for (List<String> values : rows) {
                if (++count > maxRows) {
                    throw new IllegalArgumentException("导出超过行数限制");
                }
                if (values.size() != headers.size()) {
                    throw new IllegalArgumentException("导出列数不匹配");
                }
                writeRow(sheet.createRow(count), values);
            }
            workbook.write(output);
        }
    }

    /**
     * 生成仅含表头的导入模板。
     * @param headers 按业务列顺序排列的表头
     * @return XLSX 模板的字节内容
     * @throws IOException 工作簿生成失败
     */
    public static byte[] template(List<String> headers) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, headers, List.of(), 1);
        return out.toByteArray();
    }

    /** 校验调用方配置，避免无界读取。 */
    private static void validate(List<String> headers, int maxBytes, int maxRows) {
        if (headers == null
                || headers.isEmpty()
                || headers.stream().anyMatch(s -> s == null || s.isBlank())
                || headers.stream().distinct().count() != headers.size()
                || maxBytes < 1
                || maxBytes == Integer.MAX_VALUE
                || maxRows < 1) {
            throw new IllegalArgumentException("表头或限制无效");
        }
    }

    /** 所有单元格按文本写入，防止以等号开头的内容变成公式。 */
    private static void writeRow(Row row, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            row.createCell(i, CellType.STRING).setCellValue(values.get(i) == null ? "" : values.get(i));
        }
    }

    /** 拒绝公式单元格，保留展示文本供业务代码解释。 */
    private static String value(Cell cell, DataFormatter formatter) {
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.FORMULA) {
            throw new IllegalArgumentException("不支持公式单元格");
        }
        return formatter.formatCellValue(cell);
    }
}
