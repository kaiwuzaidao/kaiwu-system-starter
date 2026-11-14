-- 可选证据保留扩展：业务项目新Flyway迁移中应用，应用前不得启用retention-enabled。
CREATE TABLE IF NOT EXISTS kaiwu_file_reference (
 file_id CHAR(36) NOT NULL,
 owner_service VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 reference_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(file_id,owner_service,reference_id),
 CONSTRAINT fk_file_reference_file FOREIGN KEY(file_id) REFERENCES kaiwu_file(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
