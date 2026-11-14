package com.kaiwu.datascope;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.InnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * 启动自检：把「接了模块却没真正生效」变成启动失败，而不是上线后的越权。
 *
 * <p>检查：拦截器已注册且早于分页；清单里的表和列真实存在；受控实体的归属字段声明为插入填充、
 * 禁止更新，并且确实被某个 {@code MetaObjectHandler} 填充。任何一项不满足都抛出异常，应用无法启动。</p>
 */
public final class DataScopeStartupVerifier implements SmartInitializingSingleton {

    private final DataScopeRegistry registry;
    private final DataScopeInnerInterceptor interceptor;
    private final ObjectProvider<MybatisPlusInterceptor> mybatisInterceptors;
    private final ObjectProvider<MetaObjectHandler> handlers;
    private final ObjectProvider<DataSource> dataSource;

    /** 注入被检查的各个部件；全部通过 {@link ObjectProvider} 延迟取得，避免过早初始化。 */
    public DataScopeStartupVerifier(
            DataScopeRegistry registry,
            DataScopeInnerInterceptor interceptor,
            ObjectProvider<MybatisPlusInterceptor> mybatisInterceptors,
            ObjectProvider<MetaObjectHandler> handlers,
            ObjectProvider<DataSource> dataSource) {
        this.registry = registry;
        this.interceptor = interceptor;
        this.mybatisInterceptors = mybatisInterceptors;
        this.handlers = handlers;
        this.dataSource = dataSource;
    }

    /** 容器完成单例初始化后执行全部检查。 */
    @Override
    public void afterSingletonsInstantiated() {
        List<String> problems = new ArrayList<>();
        checkInterceptorOrder(problems);
        checkSchema(problems);
        checkEntities(problems);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("kaiwu-data-scope 启动自检失败：\n - " + String.join("\n - ", problems));
        }
    }

    private void checkInterceptorOrder(List<String> problems) {
        List<MybatisPlusInterceptor> all = mybatisInterceptors.orderedStream().toList();
        if (all.isEmpty()) {
            problems.add("找不到 MybatisPlusInterceptor，数据范围拦截器没有注册");
            return;
        }
        boolean registered = false;
        for (MybatisPlusInterceptor holder : all) {
            List<InnerInterceptor> inner = holder.getInterceptors();
            int mine = inner.indexOf(interceptor);
            if (mine < 0) {
                continue;
            }
            registered = true;
            for (int i = 0; i < mine; i++) {
                if (inner.get(i) instanceof PaginationInnerInterceptor) {
                    problems.add("DataScopeInnerInterceptor 必须注册在 PaginationInnerInterceptor 之前");
                }
            }
        }
        if (!registered) {
            problems.add("DataScopeInnerInterceptor 没有加入 MybatisPlusInterceptor，"
                    + "请在配置里 dataScope.ifAvailable(interceptor::addInnerInterceptor)，且在分页之前");
        }
    }

    private void checkSchema(List<String> problems) {
        DataSource source = dataSource.getIfAvailable();
        if (source == null) {
            problems.add("找不到 DataSource，无法核对清单里的表和列");
            return;
        }
        try (Connection connection = source.getConnection()) {
            for (DataScopeResource resource : registry.resources()) {
                Set<String> columns = columnsOf(connection, resource.table());
                if (columns.isEmpty()) {
                    problems.add("表不存在：" + resource.table() + "（" + resource.code() + "）");
                    continue;
                }
                for (String column : List.of(resource.ownerUserColumn(), resource.ownerDeptColumn())) {
                    if (!columns.contains(column)) {
                        problems.add("列不存在：" + resource.table() + "." + column);
                    }
                }
            }
        } catch (java.sql.SQLException failure) {
            problems.add("读取数据库元数据失败：" + failure.getMessage());
        }
    }

    private static Set<String> columnsOf(Connection connection, String table) throws java.sql.SQLException {
        Set<String> columns = new HashSet<>();
        var metadata = connection.getMetaData();
        for (String name : List.of(table, table.toUpperCase(Locale.ROOT))) {
            try (ResultSet rows = metadata.getColumns(connection.getCatalog(), connection.getSchema(), name, "%")) {
                while (rows.next()) {
                    columns.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                }
            }
            if (!columns.isEmpty()) {
                break;
            }
        }
        return columns;
    }

    private void checkEntities(List<String> problems) {
        List<MetaObjectHandler> filling = handlers.orderedStream().toList();
        for (TableInfo info : TableInfoHelper.getTableInfos()) {
            DataScopeResource resource = registry.byTable(info.getTableName()).orElse(null);
            if (resource == null) {
                continue;
            }
            Class<?> type = info.getEntityType();
            for (String column : List.of(resource.ownerUserColumn(), resource.ownerDeptColumn())) {
                TableFieldInfo field = info.getFieldList().stream()
                        .filter(f -> DataScopeRegistry.normalize(f.getColumn()).equals(column))
                        .findFirst()
                        .orElse(null);
                if (field == null) {
                    problems.add(type.getSimpleName() + " 缺少归属字段 " + column);
                    continue;
                }
                if (field.getFieldFill() != FieldFill.INSERT && field.getFieldFill() != FieldFill.INSERT_UPDATE) {
                    problems.add(type.getSimpleName() + "." + field.getProperty()
                            + " 必须声明 @TableField(fill = FieldFill.INSERT)");
                }
                if (field.getUpdateStrategy() != FieldStrategy.NEVER) {
                    problems.add(type.getSimpleName() + "." + field.getProperty()
                            + " 必须声明 updateStrategy = FieldStrategy.NEVER，归属只能经 DataScopes.reassign 修改");
                }
            }
            if (!probe(type, resource, filling)) {
                problems.add(type.getSimpleName() + " 的归属字段没有被任何 MetaObjectHandler 填充，"
                        + "请在项目的 insertFill 中委托 DataScopeOwnerFiller.fill");
            }
        }
    }

    /** 对一个空实体跑一遍插入填充，看两个归属字段是否都被写入。 */
    private boolean probe(Class<?> type, DataScopeResource resource, List<MetaObjectHandler> filling) {
        String userProperty = DataScopeOwnerFiller.property(type, resource.ownerUserColumn());
        String deptProperty = DataScopeOwnerFiller.property(type, resource.ownerDeptColumn());
        if (userProperty == null || deptProperty == null) {
            return false;
        }
        for (MetaObjectHandler handler : filling) {
            try {
                Object entity = type.getDeclaredConstructor().newInstance();
                var meta = SystemMetaObject.forObject(entity);
                DataScopes.probe(() -> handler.insertFill(meta));
                if (meta.getValue(userProperty) != null && meta.getValue(deptProperty) != null) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 探测失败视为该 handler 没有可靠地填充，继续看下一个。
            }
        }
        return false;
    }
}
