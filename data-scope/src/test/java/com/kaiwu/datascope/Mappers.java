package com.kaiwu.datascope;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 测试用实体与 Mapper：覆盖 Wrapper 路径和手写 SQL 路径。 */
final class Mappers {

    private Mappers() {}

    /** 受控实体：归属字段按约定声明插入填充、禁止更新。 */
    @TableName("crm_customer")
    static class Customer {
        @TableId(type = IdType.AUTO)
        Long id;

        String name;

        @TableField(fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
        Long ownerUserId;

        @TableField(fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
        Long ownerDeptId;
    }

    /** 受控实体：合同。 */
    @TableName("crm_contract")
    static class Contract {
        @TableId(type = IdType.AUTO)
        Long id;

        Long customerId;
        String title;

        @TableField(fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
        Long ownerUserId;

        @TableField(fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
        Long ownerDeptId;
    }

    /** 豁免表实体。 */
    @TableName("crm_industry")
    static class Industry {
        @TableId(type = IdType.INPUT)
        Long id;

        String name;
    }

    /** 客户 Mapper。 */
    interface CustomerMapper extends BaseMapper<Customer> {
        @Select("SELECT c.name AS customer, k.title AS contract FROM crm_customer c "
                + "JOIN crm_contract k ON k.customer_id = c.id ORDER BY c.name, k.title")
        List<Map<String, Object>> innerJoin();

        @Select("SELECT c.name AS customer, k.title AS contract FROM crm_customer c "
                + "LEFT JOIN crm_contract k ON k.customer_id = c.id ORDER BY c.name, k.title")
        List<Map<String, Object>> leftJoin();

        @Select("SELECT name FROM crm_customer WHERE id IN (SELECT customer_id FROM crm_contract) ORDER BY name")
        List<String> inSubquery();

        @Select("SELECT name FROM crm_customer UNION ALL SELECT title FROM crm_contract ORDER BY 1")
        List<String> union();

        @Select("SELECT name FROM crm_customer WHERE name LIKE #{prefix} ORDER BY name LIMIT #{limit}")
        List<String> byPrefix(@Param("prefix") String prefix, @Param("limit") int limit);

        @Update("UPDATE crm_customer SET name = #{name} WHERE id = #{id}")
        int rename(@Param("id") long id, @Param("name") String name);

        @Update("UPDATE crm_customer SET owner_user_id = #{owner} WHERE id = #{id}")
        int setOwner(@Param("id") long id, @Param("owner") long owner);

        @Update("UPDATE crm_customer SET owner_user_id = #{user}, owner_dept_id = #{dept} WHERE id = #{id}")
        int reassign(@Param("id") long id, @Param("user") long user, @Param("dept") long dept);

        @Update("UPDATE crm_customer SET owner_user_id = #{user}, owner_dept_id = #{dept} "
                + "WHERE owner_user_id = #{from}")
        int reassignAll(@Param("from") long from, @Param("user") long user, @Param("dept") long dept);

        @Delete("DELETE FROM crm_customer WHERE id = #{id}")
        int remove(@Param("id") long id);

        @Insert("INSERT INTO crm_customer(name, owner_user_id, owner_dept_id) VALUES (#{name}, 9, 9) "
                + "ON DUPLICATE KEY UPDATE owner_user_id = 9")
        int upsert(@Param("name") String name);

        @Insert("REPLACE INTO crm_customer(name, owner_user_id, owner_dept_id) VALUES (#{name}, 9, 9)")
        int replace(@Param("name") String name);

        @Insert("INSERT INTO crm_customer(name, owner_user_id, owner_dept_id) "
                + "SELECT CONCAT(name, '-copy'), 77, 77 FROM crm_customer")
        int copyVisible();

        @Select("SELECT name FROM crm_customer WHERE ??? FOO BAR")
        List<String> unparsable();

        @Select("SELECT name FROM crm_customer ORDER BY name")
        org.apache.ibatis.cursor.Cursor<String> cursor();

        @Select("SELECT name FROM crm_industry ORDER BY 1")
        List<String> industries();
    }

    /** 合同 Mapper。 */
    interface ContractMapper extends BaseMapper<Contract> {}

    /** 豁免表 Mapper。 */
    interface IndustryMapper extends BaseMapper<Industry> {}
}
