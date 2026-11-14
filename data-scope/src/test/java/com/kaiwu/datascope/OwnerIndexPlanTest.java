package com.kaiwu.datascope;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** 用 EXPLAIN 确认谓词 {@code owner_user_id = ? OR owner_dept_id IN (…)} 能用上两个单列索引，结果写进验证记录。 */
class OwnerIndexPlanTest {

    /** 造 2 万行（2000 个用户 × 200 个部门）后看执行计划。 */
    @Test
    void bothSingleColumnIndexesAreUsable() throws Exception {
        try (MySqlHarness harness = new MySqlHarness();
                Connection connection = harness.dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO crm_customer(name, owner_user_id, owner_dept_id) VALUES (?, ?, ?)")) {
                for (int i = 0; i < 20_000; i++) {
                    insert.setString(1, "c" + i);
                    insert.setLong(2, i % 2000 + 1);
                    insert.setLong(3, i % 200 + 1);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            connection.commit();
            try (Statement statement = connection.createStatement()) {
                statement.execute("ANALYZE TABLE crm_customer");
                try (var rows = statement.executeQuery(
                        "EXPLAIN SELECT * FROM crm_customer c WHERE (c.owner_user_id = 7 OR c.owner_dept_id IN (3, 4))")) {
                    rows.next();
                    System.out.printf(
                            "EXPLAIN type=%s possible_keys=%s key=%s rows=%s%n",
                            rows.getString("type"),
                            rows.getString("possible_keys"),
                            rows.getString("key"),
                            rows.getString("rows"));
                    assertThat(rows.getString("possible_keys")).contains("idx_owner_user", "idx_owner_dept");
                    assertThat(rows.getString("type")).isEqualTo("index_merge");
                }
            }
        }
    }
}
