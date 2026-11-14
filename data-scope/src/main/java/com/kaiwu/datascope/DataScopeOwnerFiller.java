package com.kaiwu.datascope;

import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.kaiwu.starter.KaiwuContext;
import com.kaiwu.starter.auth.model.ContextDataScope;
import java.util.Optional;
import org.apache.ibatis.reflection.MetaObject;

/**
 * 插入时填充受控实体的归属列。
 *
 * <p>项目已有自己的 {@code MetaObjectHandler} 时，在它的 {@code insertFill} 里先调用
 * {@link #fill(MetaObject)}。三种场景：普通请求以 Context 覆盖请求体传入的归属，防止伪造；
 * {@link DataScopes#reassign} 内以新归属为准，实体上的值若存在必须与之一致；
 * {@link DataScopes#unrestricted} 内保留实体值，两列都必须非空。</p>
 */
public final class DataScopeOwnerFiller {

    private final DataScopeRegistry registry;

    /** @param registry 受控资源清单 */
    public DataScopeOwnerFiller(DataScopeRegistry registry) {
        this.registry = registry;
    }

    /**
     * 处理一个即将插入的实体；不是受控实体时什么也不做。
     *
     * @param metaObject MyBatis-Plus 传入的实体包装
     * @throws DataScopeViolationException 归属无法确定或与允许的值不一致
     */
    public void fill(MetaObject metaObject) {
        Object entity = metaObject.getOriginalObject();
        Optional<DataScopeResource> found = resourceOf(entity.getClass());
        if (found.isEmpty()) {
            return;
        }
        DataScopeResource resource = found.get();
        String userProperty = property(entity.getClass(), resource.ownerUserColumn());
        String deptProperty = property(entity.getClass(), resource.ownerDeptColumn());
        if (userProperty == null || deptProperty == null) {
            throw new DataScopeViolationException(
                    "受控实体缺少归属字段：" + entity.getClass().getSimpleName());
        }
        DataScopes.Frame frame = DataScopes.frame();
        if (frame != null && frame.mode() == DataScopes.Mode.UNRESTRICTED) {
            require(metaObject.getValue(userProperty) != null && metaObject.getValue(deptProperty) != null, resource);
            return;
        }
        if (frame != null && frame.mode() == DataScopes.Mode.REASSIGN) {
            DataScopes.Owner owner = frame.owner();
            set(metaObject, userProperty, owner.userId(), resource);
            set(metaObject, deptProperty, owner.deptId(), resource);
            return;
        }
        KaiwuContext context = DataScopes.requireContext();
        ContextDataScope scope = context.dataScope();
        long user;
        try {
            user = Long.parseLong(context.userId());
        } catch (NumberFormatException invalid) {
            throw new DataScopeViolationException("Context 用户 ID 不是数字");
        }
        long dept = scope == null || scope.primaryDeptId() == null ? 0L : scope.primaryDeptId();
        metaObject.setValue(userProperty, convert(metaObject.getSetterType(userProperty), user));
        metaObject.setValue(deptProperty, convert(metaObject.getSetterType(deptProperty), dept));
    }

    /** 实体上已有值时必须与 reassign 声明的新归属一致，否则拒绝；没有值则按新归属填入。 */
    private static void set(MetaObject metaObject, String property, long expected, DataScopeResource resource) {
        Object current = metaObject.getValue(property);
        if (current != null && ((Number) current).longValue() != expected) {
            throw new DataScopeViolationException("实体归属与 reassign 声明的新归属不一致：" + resource.code());
        }
        metaObject.setValue(property, convert(metaObject.getSetterType(property), expected));
    }

    private static void require(boolean condition, DataScopeResource resource) {
        if (!condition) {
            throw new DataScopeViolationException("旁路插入必须显式提供归属人和归属部门：" + resource.code());
        }
    }

    private static Object convert(Class<?> type, long value) {
        if (type == Long.class || type == long.class) {
            return value;
        }
        if (type == String.class) {
            return Long.toString(value);
        }
        throw new DataScopeViolationException("归属字段类型必须是 Long 或 String");
    }

    /** @return 实体对应的受控资源，通过 MyBatis-Plus 的表元数据按表名匹配 */
    Optional<DataScopeResource> resourceOf(Class<?> entityClass) {
        TableInfo info = TableInfoHelper.getTableInfo(entityClass);
        return info == null ? Optional.empty() : registry.byTable(info.getTableName());
    }

    /** @return 实体上映射到该列的属性名；没有则为 {@code null} */
    static String property(Class<?> entityClass, String column) {
        TableInfo info = TableInfoHelper.getTableInfo(entityClass);
        if (info == null) {
            return null;
        }
        for (TableFieldInfo field : info.getFieldList()) {
            if (DataScopeRegistry.normalize(field.getColumn()).equals(column)) {
                return field.getProperty();
            }
        }
        return null;
    }
}
