package com.kaiwu.starter.storage.file;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.kaiwu.starter.storage.controller.StoredFileController;
import com.kaiwu.starter.storage.model.StoredFile;
import com.kaiwu.starter.storage.service.ObjectKeyPolicy;
import com.kaiwu.starter.storage.service.StoredFileService;
import com.kaiwu.starter.storage.support.InMemoryStoredFileRepository;
import com.kaiwu.starter.storage.support.PresigningObjectStorage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 验证「文件」管理页接口的响应包络、下载分流与错误映射（权限由 StarterAuthInterceptor 统一把关）。 */
class StoredFileControllerTest {

    /**
     * 为给定存储搭好服务与 MockMvc，并预先上传一个文件。
     *
     * @param presign 存储是否支持预签名
     * @return 上传的文件与对应的 MockMvc
     */
    private static Fixture fixture(boolean presign) {
        PresigningObjectStorage storage = new PresigningObjectStorage(presign);
        StoredFileService service = new StoredFileService(
                new InMemoryStoredFileRepository(),
                storage,
                ref -> storage,
                new ObjectKeyPolicy("demo", Clock.systemUTC()),
                Clock.systemUTC(),
                1024,
                Duration.ofMinutes(15));
        StoredFile file = service.upload(
                "invoice",
                "发票.pdf",
                "application/pdf",
                new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)),
                5,
                "7");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new StoredFileController(service, Duration.ofMinutes(5)))
                .build();
        return new Fixture(file, mvc);
    }

    /** 列表返回与生成项目一致的 {@code code/data/records} 形状。 */
    @Test
    void listReturnsResultEnvelope() throws Exception {
        Fixture f = fixture(true);

        f.mvc()
                .perform(get("/api/files").param("keyword", "发票"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(f.file().id()))
                .andExpect(jsonPath("$.data.records[0].originalName").value("发票.pdf"));
    }

    /** 支持预签名时给直连地址；不支持时给中转地址，并能以附件形式取回内容。 */
    @Test
    void downloadUsesPresignedUrlOrFallsBackToRelay() throws Exception {
        Fixture direct = fixture(true);
        direct.mvc()
                .perform(get("/api/files/{id}/download", direct.file().id()))
                .andExpect(jsonPath("$.data.direct").value(true))
                .andExpect(
                        jsonPath("$.data.url").value(org.hamcrest.Matchers.startsWith("https://storage.test/demo/")));

        Fixture relay = fixture(false);
        relay.mvc()
                .perform(get("/api/files/{id}/download", relay.file().id()))
                .andExpect(jsonPath("$.data.direct").value(false))
                .andExpect(jsonPath("$.data.url")
                        .value("/api/files/" + relay.file().id() + "/content"));
        relay.mvc()
                .perform(get("/api/files/{id}/content", relay.file().id()))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andExpect(content().string("hello"));
    }

    /** 删除后再访问返回 404，重复删除同样 404。 */
    @Test
    void deleteThenMissingFileReturns404() throws Exception {
        Fixture f = fixture(true);

        f.mvc().perform(delete("/api/files/{id}", f.file().id())).andExpect(status().isOk());
        f.mvc()
                .perform(get("/api/files/{id}/download", f.file().id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
        f.mvc().perform(delete("/api/files/{id}", f.file().id())).andExpect(status().isNotFound());
    }

    /**
     * 测试夹具。
     *
     * @param file 已上传的文件
     * @param mvc 绑定控制器的 MockMvc
     */
    private record Fixture(StoredFile file, MockMvc mvc) {}
}
