---
blueprintId: auto_medic
description: 系统自愈守护进程 — 监听 sys.kernel.panic 事件并自动修复崩溃节点
requiredParams: []
---
# AutoMedic — 系统内置，由 InitDaemon 自动拉起
# 实际逻辑由 com.ouisani.aios.user.apps.omnifactory.AutoMedicAgent 提供
import BaseAgent
import json

class AutoMedicDaemon(BaseAgent.BaseAgent):
    def process_data(self, data):
        # AutoMedic 由内核 AutoMedicAgent.java 驱动
        # 此 Python 入口仅作为 EventBus 桥接
        pass
