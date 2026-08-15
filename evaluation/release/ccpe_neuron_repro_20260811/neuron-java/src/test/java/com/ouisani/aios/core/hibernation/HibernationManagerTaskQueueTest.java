package com.ouisani.aios.core.hibernation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HibernationManager 任务队列捕获单元测试 — 验证 {@link TaskQueueSnapshotProvider}
 * 注入机制:无 provider 返回空、有 provider 委托返回、provider 抛异常降级返回空。
 * <p>
 * 通过反射直接调用私有 {@code captureTaskQueue()},隔离 suspendToDisk 的其它捕获副作用。
 */
class HibernationManagerTaskQueueTest {

    @AfterEach
    void cleanup() {
        // 单例共享状态,每个测试后清空 provider 避免污染其它测试
        HibernationManager.instance().setTaskQueueProvider(null);
    }

    @SuppressWarnings("unchecked")
    private List<AgentSnapshot.TaskState> invokeCaptureTaskQueue() throws Exception {
        Method m = HibernationManager.class.getDeclaredMethod("captureTaskQueue");
        m.setAccessible(true);
        return (List<AgentSnapshot.TaskState>) m.invoke(HibernationManager.instance());
    }

    @Test
    void captureTaskQueue_noProvider_returnsEmpty() throws Exception {
        HibernationManager.instance().setTaskQueueProvider(null);

        List<AgentSnapshot.TaskState> tasks = invokeCaptureTaskQueue();

        assertTrue(tasks.isEmpty());
    }

    @Test
    void captureTaskQueue_withProvider_delegatesAndReturnsTasks() throws Exception {
        List<AgentSnapshot.TaskState> seed = List.of(
                new AgentSnapshot.TaskState("task-1", "PENDING", "do x", 0),
                new AgentSnapshot.TaskState("task-2", "RUNNING", "do y", 0)
        );
        HibernationManager.instance().setTaskQueueProvider(() -> seed);

        List<AgentSnapshot.TaskState> tasks = invokeCaptureTaskQueue();

        assertEquals(2, tasks.size());
        assertEquals("task-1", tasks.get(0).taskId());
        assertEquals("PENDING", tasks.get(0).status());
        assertEquals("task-2", tasks.get(1).taskId());
        assertEquals("RUNNING", tasks.get(1).status());
    }

    @Test
    void captureTaskQueue_providerThrows_returnsEmpty() throws Exception {
        HibernationManager.instance().setTaskQueueProvider(() -> {
            throw new RuntimeException("boom");
        });

        List<AgentSnapshot.TaskState> tasks = invokeCaptureTaskQueue();

        assertTrue(tasks.isEmpty());
    }
}
