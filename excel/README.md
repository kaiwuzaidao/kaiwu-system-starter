# Kaiwu Excel（可选组件）

数据库无关的同步 Excel 单表格编解码。组件不暴露接口，也不访问数据库。0.2.0 随 Starter 发布到 Maven Central 后，在业务项目的 domain 模块按需添加以下依赖：

```xml
<dependency>
  <groupId>com.kaiwuzaidao</groupId>
  <artifactId>kaiwu-excel</artifactId>
  <version>0.2.0</version>
</dependency>
```

```java
var headers = List.of("订单号", "金额");
byte[] template = ExcelTable.template(headers);
var rows = ExcelTable.read(file.getInputStream(), headers, 5 * 1024 * 1024, 5000);
var result = ExcelTable.process(rows, row -> validateAndSave(row));
// 处理器返回安全的错误说明或 null；result.errors() 包含行号。
ExcelTable.write(response.getOutputStream(), headers, permittedRows, 5000);
```

接口层必须验证权限及数据范围，限制上传体积，业务层负责字段类型转换、字典/关联校验、重复处理、事务策略及审计。字段包含敏感数据时须显式排除或脱敏。导出查询和列表共享筛选及数据权限，查询先限制最多 N+1 条，超限报错，不允许静默截断。下载响应须在可能失败的查询与校验完成后再提交，避免给用户返回损坏文件。上传不输出原始内容到日志。

读取仅支持未加密 XLSX，严格要求单 sheet、顺序表头一致，拒绝公式；限制压缩文件大小、最多 256 个 ZIP 条目和累计展开大小 16 MiB，再检查行数。表头与数据保留原始首尾空格；编码时所有单元格写文本。数值、日期由业务层按明确格式转换。逐行成功或整批回滚的语义由业务显式决定；第一版不提供异步、重试、跨库迁移或自动写入。
