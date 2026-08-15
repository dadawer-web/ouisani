package com.ouisani.aios.core.remote;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModalExecutor} / {@link ModalRestExecutor} 真实 Modal 集成测试 — env-flag-gated，默认 skip。
 * <p>
 * 启用方式：
 * <pre>
 * AIOS_R4_MODAL_INTEGRATION=1
 * MODAL_TOKEN_ID=<token id>
 * MODAL_TOKEN_SECRET=<token secret>
 * MODAL_WORKSPACE=<workspace>          # 可选
 * AIOS_R4_MODAL_APP=<app 文件路径>       # CLI 模式
 * AIOS_R4_MODAL_FN=<函数名>             # CLI 模式
 * AIOS_R4_MODAL_ENDPOINT=<REST URL>     # 可选；设置后额外测 REST 路径
 * </pre>
 * flag 已设但缺 app/fn（CLI）或 endpoint（REST）时，对应测试优雅 skip。
 */
@EnabledIfEnvironmentVariable(named = "AIOS_R4_MODAL_INTEGRATION", matches = "1")
class ModalExecutorIntegrationTest {

    @Test
    @DisplayName("CLI modal run：调用预部署函数 → success")
    void cliModal_run_returnsSuccess() {
        String app = System.getenv("AIOS_R4_MODAL_APP");
        String fn = System.getenv("AIOS_R4_MODAL_FN");
        Assumptions.assumeTrue(app != null && !app.isBlank() && fn != null && !fn.isBlank(),
                "AIOS_R4_MODAL_APP/_FN 未设置 — 跳过 Modal CLI 集成测试");

        ModalExecutor executor = new ModalExecutor();
        RemoteExecutorConfig config = RemoteExecutorConfig.modal(app, fn);

        RemoteResult r = executor.execute(config, "hello", null);

        assertTrue(r.success(), "Modal CLI 应成功: " + r.errorMessage());
    }

    @Test
    @DisplayName("REST modal-rest：POST 到 Modal Web Endpoint → success")
    void restModal_post_returnsSuccess() {
        String endpoint = System.getenv("AIOS_R4_MODAL_ENDPOINT");
        String tokenId = System.getenv("MODAL_TOKEN_ID");
        String tokenSecret = System.getenv("MODAL_TOKEN_SECRET");
        Assumptions.assumeTrue(
                endpoint != null && !endpoint.isBlank()
                        && tokenId != null && !tokenId.isBlank()
                        && tokenSecret != null && !tokenSecret.isBlank(),
                "AIOS_R4_MODAL_ENDPOINT / MODAL_TOKEN_ID / MODAL_TOKEN_SECRET 未齐全 — 跳过 Modal REST 集成测试");

        String fn = System.getenv("AIOS_R4_MODAL_FN");
        if (fn == null || fn.isBlank()) fn = "handle";

        ModalRestExecutor executor = new ModalRestExecutor();
        RemoteExecutorConfig config = RemoteExecutorConfig.modalRest(endpoint, fn, tokenId, tokenSecret);

        RemoteResult r = executor.execute(config, "hello-rest", null);

        assertTrue(r.success(), "Modal REST 应成功: " + r.errorMessage());
    }
}
