#!/usr/bin/env python3
"""
Design System Architecture Agent
Based on research results, designs the overall architecture of the task queue
(including producer, storage, dispatcher, executor, monitoring, etc.)
"""

import json
import os
import sys
from datetime import datetime
from typing import Dict, List, Any


class BaseAgent:
    """Minimal BaseAgent for standalone execution."""
    def __init__(self, agent_id: str):
        self.agent_id = agent_id
        base_dir = os.path.dirname(os.path.abspath(__file__))
        self.output_path = os.path.join(base_dir, "outputs", "{}_output.json".format(agent_id))

    def process_data(self, data: Any) -> Any:
        raise NotImplementedError

    def run(self, input_data: Any = None) -> Any:
        result = self.process_data(input_data)
        self.save_output(result)
        return result

    def save_output(self, result: Any) -> None:
        os.makedirs(os.path.dirname(self.output_path), exist_ok=True)
        output = {
            "agent_id": self.agent_id,
            "timestamp": datetime.now().isoformat(),
            "result": result
        }
        with open(self.output_path, 'w', encoding='utf-8') as f:
            json.dump(output, f, indent=2, ensure_ascii=False)


class DesignSystemArchitectureAgent(BaseAgent):
    """Agent that designs the overall system architecture for a distributed task queue."""

    def __init__(self):
        super().__init__("design_system_architecture")

    def process_data(self, data: Any) -> Dict[str, Any]:
        architecture = {
            "metadata": {
                "design_name": "High-Concurrency Distributed Task Queue System",
                "version": "1.0.0",
                "timestamp": datetime.now().isoformat(),
                "designer": "DesignSystemArchitectureAgent"
            },
            "system_overview": {
                "purpose": "Handle high-concurrency task distribution and execution with fault tolerance",
                "requirements": [
                    "Support 10,000+ concurrent tasks",
                    "99.9% availability",
                    "Sub-second latency for task submission",
                    "Automatic failover and recovery",
                    "Scalable horizontally",
                    "At-least-once delivery guarantee",
                    "Task priority support",
                    "Real-time monitoring and alerting"
                ],
                "architecture_pattern": "Microservices with Event-Driven Architecture"
            },
            "core_components": self._design_core_components(),
            "data_flow": self._design_data_flow(),
            "deployment_architecture": self._design_deployment(),
            "technology_stack": self._recommend_tech_stack(),
            "scalability_strategy": self._design_scalability(),
            "fault_tolerance": self._design_fault_tolerance(),
            "monitoring_and_alerting": self._design_monitoring(),
            "api_design": self._design_api(),
            "security_considerations": self._design_security()
        }
        self._save_architecture_document(architecture)
        return architecture

    def _design_core_components(self) -> Dict[str, Any]:
        components = {}

        components["task_producer"] = {
            "description": "Component that generates and submits tasks to the queue",
            "responsibilities": [
                "Accept task submissions from clients via REST/gRPC",
                "Validate task parameters and schema",
                "Assign unique task IDs (UUID v4)",
                "Route tasks to appropriate queues based on priority/type",
                "Handle backpressure when queue is full"
            ],
            "interfaces": {
                "input": "HTTP REST API / gRPC / SDK",
                "output": "Task message to storage layer"
            },
            "scaling": "Stateless, horizontally scalable behind load balancer"
        }

        components["task_storage"] = {
            "description": "Persistent storage layer for task metadata and state",
            "responsibilities": [
                "Store task definitions and metadata",
                "Track task lifecycle states (PENDING, RUNNING, COMPLETED, FAILED)",
                "Provide atomic operations for task state transitions",
                "Support task querying and filtering",
                "Maintain task execution history"
            ],
            "technology": "Redis for hot data + PostgreSQL for persistence",
            "data_model": {
                "task": {
                    "id": "UUID",
                    "type": "string",
                    "priority": "integer (0-10)",
                    "payload": "JSON object",
                    "status": "enum",
                    "created_at": "timestamp",
                    "updated_at": "timestamp",
                    "retry_count": "integer",
                    "max_retries": "integer",
                    "timeout": "duration",
                    "result": "JSON object (nullable)"
                }
            }
        }

        components["task_queue"] = {
            "description": "Message queue for task distribution",
            "responsibilities": [
                "Buffer incoming tasks",
                "Support priority-based ordering",
                "Enable pub/sub for real-time notifications",
                "Provide persistence for durability",
                "Support dead letter queue for failed tasks"
            ],
            "technology": "Redis Streams or Apache Kafka",
            "queue_types": [
                "high_priority_queue",
                "normal_priority_queue",
                "low_priority_queue",
                "dead_letter_queue"
            ]
        }

        components["task_dispatcher"] = {
            "description": "Distributes tasks to available executors",
            "responsibilities": [
                "Monitor queue for pending tasks",
                "Match tasks to appropriate executors based on capability",
                "Implement load balancing across executors",
                "Handle executor failures and task reassignment",
                "Manage task timeouts and retries"
            ],
            "algorithms": [
                "Round-robin for equal distribution",
                "Least-connections for load balancing",
                "Capability-based routing for specialized tasks"
            ],
            "scaling": "Active-passive with leader election"
        }

        components["task_executor"] = {
            "description": "Worker that processes tasks",
            "responsibilities": [
                "Pull tasks from queue",
                "Execute task logic",
                "Report task status and progress",
                "Handle task cancellation",
                "Upload results to storage"
            ],
            "scaling": "Auto-scaling based on queue depth and CPU utilization",
            "lifecycle": {
                "states": ["IDLE", "PULLING", "EXECUTING", "REPORTING"],
                "heartbeat_interval": "10 seconds",
                "task_timeout": "configurable per task type"
            }
        }

        components["task_monitor"] = {
            "description": "Monitoring and alerting component",
            "responsibilities": [
                "Collect metrics from all components",
                "Track task completion rates and latencies",
                "Alert on system anomalies",
                "Provide dashboard for real-time visibility",
                "Store historical metrics for analysis"
            ],
            "metrics": [
                "tasks_per_second",
                "average_latency",
                "queue_depth",
                "executor_utilization",
                "error_rate",
                "retry_rate"
            ],
            "technology": "Prometheus + Grafana"
        }

        components["task_scheduler"] = {
            "description": "Handles scheduled and recurring tasks",
            "responsibilities": [
                "Schedule tasks for future execution",
                "Support cron-like recurring patterns",
                "Manage task dependencies (DAG execution)",
                "Handle timezone conversions"
            ],
            "technology": "Custom scheduler with Redis-based timing wheel"
        }

        return components

    def _design_data_flow(self) -> Dict[str, Any]:
        return {
            "task_submission_flow": {
                "steps": [
                    "1. Client submits task via REST API",
                    "2. Producer validates and assigns task ID",
                    "3. Task stored in database with PENDING status",
                    "4. Task enqueued to appropriate priority queue",
                    "5. Producer returns task ID to client",
                    "6. Dispatcher picks task from queue",
                    "7. Dispatcher assigns to available executor",
                    "8. Executor updates task status to RUNNING",
                    "9. Executor processes task",
                    "10. Executor updates status to COMPLETED/FAILED",
                    "11. Results stored in database",
                    "12. Client notified via webhook/polling"
                ]
            },
            "retry_flow": {
                "steps": [
                    "1. Executor reports task failure",
                    "2. Retry manager checks retry count",
                    "3. If retries < max_retries: re-enqueue with backoff",
                    "4. If retries >= max_retries: move to dead letter queue",
                    "5. Alert notification sent for dead letter tasks"
                ]
            },
            "monitoring_flow": {
                "steps": [
                    "1. Components emit metrics via StatsD/Prometheus",
                    "2. Metrics aggregated in time-series database",
                    "3. Alert rules evaluated continuously",
                    "4. Dashboard queries metrics for visualization",
                    "5. Anomalies trigger PagerDuty/Slack alerts"
                ]
            }
        }

    def _design_deployment(self) -> Dict[str, Any]:
        return {
            "containerization": "Docker containers orchestrated by Kubernetes",
            "services": {
                "api_gateway": {
                    "replicas": "3+",
                    "resources": "2 CPU, 4GB RAM",
                    "scaling": "HPA based on request count"
                },
                "task_producer": {
                    "replicas": "3+",
                    "resources": "1 CPU, 2GB RAM",
                    "scaling": "HPA based on CPU"
                },
                "task_dispatcher": {
                    "replicas": "2 (active-passive)",
                    "resources": "2 CPU, 4GB RAM",
                    "scaling": "Manual with leader election"
                },
                "task_executor": {
                    "replicas": "10-100 (auto-scaling)",
                    "resources": "4 CPU, 8GB RAM",
                    "scaling": "HPA based on queue depth"
                },
                "redis": {
                    "replicas": "3 (sentinel mode)",
                    "resources": "4 CPU, 16GB RAM",
                    "persistence": "AOF + RDB snapshots"
                },
                "postgresql": {
                    "replicas": "2 (primary-replica)",
                    "resources": "4 CPU, 16GB RAM",
                    "persistence": "EBS volumes with daily backups"
                }
            },
            "networking": {
                "service_mesh": "Istio for inter-service communication",
                "load_balancer": "NGINX Ingress Controller",
                "tls": "mTLS between services, TLS termination at ingress"
            }
        }

    def _recommend_tech_stack(self) -> Dict[str, Any]:
        return {
            "languages": {
                "api_services": "Python (FastAPI) or Go",
                "executors": "Python or language-agnostic via containers",
                "infrastructure": "Terraform, Helm charts"
            },
            "frameworks": {
                "api": "FastAPI with Pydantic for validation",
                "task_execution": "Celery or custom executor framework",
                "monitoring": "Prometheus client library"
            },
            "databases": {
                "cache": "Redis 7.x with RedisJSON module",
                "primary": "PostgreSQL 15 with JSON support",
                "search": "Elasticsearch for task search (optional)"
            },
            "messaging": {
                "primary": "Redis Streams for simplicity",
                "alternative": "Apache Kafka for high throughput"
            },
            "monitoring": {
                "metrics": "Prometheus",
                "visualization": "Grafana",
                "logging": "ELK Stack (Elasticsearch, Logstash, Kibana)",
                "tracing": "Jaeger for distributed tracing"
            }
        }

    def _design_scalability(self) -> Dict[str, Any]:
        return {
            "horizontal_scaling": {
                "executors": "Auto-scale based on queue depth (target: 100 tasks/executor)",
                "producers": "Stateless, scale behind load balancer",
                "dispatchers": "Active-passive with automatic failover"
            },
            "vertical_scaling": {
                "redis": "Increase memory for larger queue capacity",
                "postgresql": "Increase CPU for faster queries"
            },
            "sharding_strategy": {
                "task_queues": "Shard by task type or tenant ID",
                "storage": "Partition by task creation date"
            },
            "caching_strategy": {
                "task_metadata": "Redis with 5-minute TTL",
                "executor_status": "Redis with 30-second TTL",
                "queue_stats": "Redis with 10-second TTL"
            }
        }

    def _design_fault_tolerance(self) -> Dict[str, Any]:
        return {
            "task_retries": {
                "max_retries": 3,
                "backoff_strategy": "Exponential backoff (1s, 4s, 16s)",
                "jitter": "Add random jitter to prevent thundering herd"
            },
            "dead_letter_queue": {
                "purpose": "Store tasks that exceeded max retries",
                "retention": "7 days",
                "alerting": "Immediate alert on DLQ insertion"
            },
            "circuit_breaker": {
                "enabled": True,
                "failure_threshold": 5,
                "reset_timeout": "30 seconds",
                "half_open_requests": 3
            },
            "health_checks": {
                "component_health": "HTTP /health endpoint",
                "heartbeat": "Every 10 seconds",
                "timeout": "30 seconds for unhealthy detection"
            },
            "data_durability": {
                "redis": "AOF with fsync every second",
                "postgresql": "WAL archiving with point-in-time recovery",
                "backups": "Daily full backups, hourly incremental"
            }
        }

    def _design_monitoring(self) -> Dict[str, Any]:
        return {
            "key_metrics": {
                "system_metrics": [
                    "CPU utilization per component",
                    "Memory usage",
                    "Network I/O",
                    "Disk I/O"
                ],
                "application_metrics": [
                    "Tasks submitted per second",
                    "Tasks completed per second",
                    "Task latency (p50, p95, p99)",
                    "Queue depth per queue",
                    "Executor utilization percentage",
                    "Error rate by task type",
                    "Retry rate"
                ]
            },
            "alerts": [
                {
                    "name": "High Error Rate",
                    "condition": "error_rate > 5% for 5 minutes",
                    "severity": "critical",
                    "notification": "PagerDuty + Slack"
                },
                {
                    "name": "Queue Backlog",
                    "condition": "queue_depth > 10000 for 10 minutes",
                    "severity": "warning",
                    "notification": "Slack"
                },
                {
                    "name": "Executor Down",
                    "condition": "executor_health_check_failed for 30 seconds",
                    "severity": "critical",
                    "notification": "PagerDuty"
                },
                {
                    "name": "High Latency",
                    "condition": "p99_latency > 10s for 5 minutes",
                    "severity": "warning",
                    "notification": "Slack"
                }
            ],
            "dashboards": [
                "System Overview - All component health",
                "Task Metrics - Submission, completion, error rates",
                "Queue Metrics - Depth, throughput, latency",
                "Executor Metrics - Utilization, task distribution",
                "Infrastructure - CPU, memory, network"
            ]
        }

    def _design_api(self) -> Dict[str, Any]:
        return {
            "task_api": {
                "base_url": "/api/v1",
                "endpoints": [
                    {
                        "method": "POST",
                        "path": "/tasks",
                        "description": "Submit a new task",
                        "request_body": {
                            "type": "string (required)",
                            "payload": "object (required)",
                            "priority": "integer (optional, default 5)",
                            "timeout": "integer (optional, default 300 seconds)",
                            "callback_url": "string (optional)"
                        },
                        "response": {
                            "task_id": "UUID",
                            "status": "PENDING",
                            "created_at": "timestamp"
                        }
                    },
                    {
                        "method": "GET",
                        "path": "/tasks/{task_id}",
                        "description": "Get task status and details",
                        "response": {
                            "task_id": "UUID",
                            "status": "string",
                            "result": "object (nullable)",
                            "created_at": "timestamp",
                            "updated_at": "timestamp"
                        }
                    },
                    {
                        "method": "DELETE",
                        "path": "/tasks/{task_id}",
                        "description": "Cancel a pending task",
                        "response": {
                            "task_id": "UUID",
                            "status": "CANCELLED"
                        }
                    },
                    {
                        "method": "GET",
                        "path": "/tasks",
                        "description": "List tasks with filtering",
                        "query_params": {
                            "status": "filter by status",
                            "type": "filter by task type",
                            "created_after": "filter by creation date",
                            "limit": "pagination limit",
                            "offset": "pagination offset"
                        }
                    },
                    {
                        "method": "GET",
                        "path": "/queues/stats",
                        "description": "Get queue statistics",
                        "response": {
                            "queues": "array of queue stats",
                            "total_pending": "integer",
                            "total_running": "integer"
                        }
                    }
                ]
            },
            "webhook_api": {
                "base_url": "/api/v1/webhooks",
                "endpoints": [
                    {
                        "method": "POST",
                        "path": "/register",
                        "description": "Register webhook for task completion notifications"
                    },
                    {
                        "method": "DELETE",
                        "path": "/{webhook_id}",
                        "description": "Unregister webhook"
                    }
                ]
            }
        }

    def _design_security(self) -> Dict[str, Any]:
        return {
            "authentication": {
                "method": "API Key or JWT tokens",
                "implementation": "Middleware validates tokens on each request"
            },
            "authorization": {
                "model": "RBAC (Role-Based Access Control)",
                "roles": ["admin", "operator", "viewer"],
                "permissions": "Per-queue and per-task-type access control"
            },
            "data_security": {
                "encryption_at_rest": "AES-256 for sensitive task payloads",
                "encryption_in_transit": "TLS 1.3 for all communications",
                "pii_handling": "Automatic detection and masking of PII in logs"
            },
            "rate_limiting": {
                "default": "100 requests per minute per API key",
                "burst": "10 requests per second",
                "implementation": "Token bucket algorithm in Redis"
            },
            "audit_logging": {
                "events": [
                    "Task submission",
                    "Task status changes",
                    "Configuration changes",
                    "Authentication events"
                ],
                "retention": "90 days",
                "storage": "Elasticsearch with restricted access"
            }
        }

    def _save_architecture_document(self, architecture: Dict[str, Any]) -> None:
        base_dir = os.path.dirname(os.path.abspath(__file__))
        doc_path = os.path.join(base_dir, "outputs", "architecture_design.json")
        os.makedirs(os.path.dirname(doc_path), exist_ok=True)
        with open(doc_path, 'w', encoding='utf-8') as f:
            json.dump(architecture, f, indent=2, ensure_ascii=False)


def main():
    """Main entry point."""
    print("DESIGN_SYSTEM_ARCHITECTURE_AGENT_START", flush=True)

    agent = DesignSystemArchitectureAgent()

    input_data = {
        "task": "design_architecture",
        "context": "High-concurrency distributed task queue",
        "requirements": {
            "concurrency": 10000,
            "availability": "99.9%",
            "latency": "sub-second"
        }
    }

    result = agent.run(input_data)

    print("AGENT_SUCCESS: Architecture design completed", flush=True)
    print("Components designed: {}".format(len(result.get("core_components", {}))), flush=True)

    output = {
        "agent_id": "design_system_architecture",
        "status": "success",
        "timestamp": datetime.now().isoformat(),
        "result": result,
        "output_files": [
            "/factory/outputs/architecture_design.json",
            "/factory/outputs/design_system_architecture_output.json"
        ],
        "components_designed": len(result.get("core_components", {})),
        "message": "Architecture design completed successfully"
    }

    print(json.dumps(output, indent=2, ensure_ascii=False), flush=True)
    print("DESIGN_SYSTEM_ARCHITECTURE_AGENT_SUCCESS", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())