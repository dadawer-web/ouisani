#!/usr/bin/env python3
"""
Documentation Generator Node for AIOS Workflow
Generates comprehensive README documentation for a distributed task queue project.
Includes architecture diagram, quick start guide, API reference, and configuration details.
"""

import os
import json
import sys
from datetime import datetime

# Import BaseAgent from AIOS framework
try:
    from aios.base_agent import BaseAgent
except ImportError:
    # Fallback for local testing
    class BaseAgent:
        def __init__(self, agent_id, config=None):
            self.agent_id = agent_id
            self.config = config or {}
        
        def process_data(self, data):
            raise NotImplementedError("Subclasses must implement process_data method")
        
        def run(self, input_data=None):
            return self.process_data(input_data)


class DocumentationGenerator(BaseAgent):
    """
    Generates comprehensive README documentation for a distributed task queue project.
    Creates documentation with architecture diagrams, quick start guides, API references,
    and configuration details in Markdown format.
    """
    
    def __init__(self, agent_id="doc_generator", config=None):
        super().__init__(agent_id, config)
        self.project_name = config.get("project_name", "Distributed Task Queue")
        self.project_version = config.get("project_version", "1.0.0")
        self.output_dir = "/shared/outputs"
        
    def process_data(self, data):
        """
        Process input data and generate documentation.
        
        Args:
            data (dict): Input data containing project information
            
        Returns:
            dict: Result with status and output path
        """
        print(f"📝 [{self.agent_id}] Starting documentation generation...", flush=True)
        
        try:
            # Ensure output directory exists
            os.makedirs(self.output_dir, exist_ok=True)
            
            # Generate documentation content
            doc_content = self._generate_documentation_content(data)
            
            # Write to output file
            output_path = os.path.join(self.output_dir, "README.md")
            with open(output_path, 'w', encoding='utf-8') as f:
                f.write(doc_content)
            
            # Also generate a JSON summary
            summary = {
                "project_name": self.project_name,
                "version": self.project_version,
                "generated_at": datetime.now().isoformat(),
                "sections": [
                    "Project Overview",
                    "Architecture Diagram", 
                    "Quick Start Guide",
                    "API Reference",
                    "Configuration",
                    "Contributing",
                    "License"
                ],
                "output_file": output_path,
                "file_size": len(doc_content)
            }
            
            summary_path = os.path.join(self.output_dir, "doc_summary.json")
            with open(summary_path, 'w', encoding='utf-8') as f:
                json.dump(summary, f, indent=2, ensure_ascii=False)
            
            print(f"✅ [{self.agent_id}] Documentation generated successfully!", flush=True)
            print(f"📄 Main document: {output_path}", flush=True)
            print(f"📊 Summary: {summary_path}", flush=True)
            
            return {
                "status": "success",
                "output_path": output_path,
                "summary_path": summary_path,
                "sections_count": 7,
                "timestamp": datetime.now().isoformat()
            }
            
        except Exception as e:
            print(f"❌ [{self.agent_id}] Documentation generation failed: {str(e)}", flush=True)
            return {
                "status": "error",
                "error": str(e),
                "timestamp": datetime.now().isoformat()
            }
    
    def _generate_documentation_content(self, data):
        """
        Generate the complete documentation content.
        
        Args:
            data (dict): Project information
            
        Returns:
            str: Complete README content in Markdown format
        """
        project_info = data.get("project_info", {})
        components = data.get("components", [])
        api_endpoints = data.get("api_endpoints", [])
        config_options = data.get("config_options", [])
        
        # Build README content
        content = []
        
        # Header
        content.append(self._generate_header())
        content.append("")
        
        # Table of Contents
        content.append(self._generate_toc())
        content.append("")
        
        # Project Overview
        content.append(self._generate_overview(project_info))
        content.append("")
        
        # Architecture Diagram
        content.append(self._generate_architecture(components))
        content.append("")
        
        # Quick Start Guide
        content.append(self._generate_quick_start())
        content.append("")
        
        # API Reference
        content.append(self._generate_api_reference(api_endpoints))
        content.append("")
        
        # Configuration
        content.append(self._generate_configuration(config_options))
        content.append("")
        
        # Contributing and License
        content.append(self._generate_footer())
        
        return "\n".join(content)
    
    def _generate_header(self):
        """Generate the document header."""
        return f"""# {self.project_name}

**Version:** {self.project_version}  
**Generated:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}  
**Platform:** AIOS Distributed Task Queue

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Python 3.8+](https://img.shields.io/badge/python-3.8+-blue.svg)](https://www.python.org/downloads/)
[![Build Status](https://img.shields.io/badge/build-passing-brightgreen)](https://github.com)

A high-performance, fault-tolerant distributed task queue system designed for scalable microservice architectures."""
    
    def _generate_toc(self):
        """Generate table of contents."""
        return """## Table of Contents

1. [Project Overview](#project-overview)
2. [Architecture Diagram](#architecture-diagram)
3. [Quick Start Guide](#quick-start-guide)
4. [API Reference](#api-reference)
5. [Configuration](#configuration)
6. [Contributing](#contributing)
7. [License](#license)"""
    
    def _generate_overview(self, project_info):
        """Generate project overview section."""
        features = project_info.get("features", [
            "Distributed task scheduling with load balancing",
            "Fault tolerance with automatic task retry",
            "Real-time monitoring and metrics",
            "RESTful API for task management",
            "Plugin architecture for extensibility"
        ])
        
        feature_list = "\n".join([f"- {feature}" for feature in features])
        
        return f"""## Project Overview

{project_info.get('description', 'A distributed task queue system designed for modern cloud-native applications.')}

### Key Features

{feature_list}

### System Requirements

- **Python**: 3.8 or higher
- **Redis**: 6.0+ (for task queue backend)
- **Database**: PostgreSQL 12+ or MySQL 8.0+ (for task metadata)
- **OS**: Linux, macOS, or Windows with WSL2

### Architecture Principles

- **Microservices Ready**: Designed for containerized deployments
- **Horizontal Scalability**: Add workers dynamically based on load
- **Event-Driven**: Async communication between components
- **Observable**: Built-in metrics and logging"""
    
    def _generate_architecture(self, components):
        """Generate architecture diagram section."""
        default_components = [
            {"name": "API Gateway", "type": "service", "port": 8000},
            {"name": "Task Scheduler", "type": "core", "port": 8001},
            {"name": "Worker Pool", "type": "compute", "port": "dynamic"},
            {"name": "Redis Queue", "type": "storage", "port": 6379},
            {"name": "PostgreSQL DB", "type": "storage", "port": 5432},
            {"name": "Monitoring", "type": "service", "port": 9090}
        ]
        
        components = components or default_components
        
        # Generate ASCII architecture diagram
        diagram_lines = [
            "```",
            "┌─────────────────────────────────────────────────────────────┐",
            "│                  DISTRIBUTED TASK QUEUE ARCHITECTURE        │",
            "├─────────────────────────────────────────────────────────────┤",
            "│                                                             │",
            "│    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐   │",
            "│    │   Client    │    │   Client    │    │   Client    │   │",
            "│    │  Applications│    │  Applications│    │  Applications│   │",
            "│    └──────┬───────┘    └──────┬───────┘    └──────┬───────┘   │",
            "│           │                   │                   │           │",
            "│           ▼                   ▼                   ▼           │",
            "│    ┌─────────────────────────────────────────────────────┐   │",
            "│    │              API Gateway (Port: 8000)               │   │",
            "│    │    • REST API  • Authentication  • Rate Limiting    │   │",
            "│    └─────────────────────────┬─────────────────────────┘   │",
            "│                             │                             │",
            "│                             ▼                             │",
            "│    ┌─────────────────────────────────────────────────────┐   │",
            "│    │            Task Scheduler (Port: 8001)              │   │",
            "│    │  • Task Prioritization  • Load Balancing            │   │",
            "│    │  • Dependency Resolution • Retry Logic             │   │",
            "│    └─────────────────────────┬─────────────────────────┘   │",
            "│                             │                             │",
            "│           ┌─────────────────┼─────────────────┐           │",
            "│           ▼                 ▼                 ▼           │",
            "│    ┌─────────────┐    ┌─────────────┐    ┌─────────────┐   │",
            "│    │  Worker 1   │    │  Worker 2   │    │  Worker N   │   │",
            "│    │  (Port:     │    │  (Port:     │    │  (Port:     │   │",
            "│    │  dynamic)   │    │  dynamic)   │    │  dynamic)   │   │",
            "│    └─────────────┘    └─────────────┘    └─────────────┘   │",
            "│                                                             │",
            "│    ┌─────────────────────────────────────────────────────┐   │",
            "│    │                 Storage Layer                       │   │",
            "│    │  ┌───────────────┐    ┌───────────────────────┐   │   │",
            "│    │  │ Redis Queue   │    │ PostgreSQL Database    │   │   │",
            "│    │  │ (Port: 6379)  │    │ (Port: 5432)          │   │   │",
            "│    │  │ • Task Queue  │    │ • Task Metadata       │   │   │",
            "│    │  │ • Pub/Sub     │    │ • User Data           │   │   │",
            "│    │  └───────────────┘    └───────────────────────┘   │   │",
            "│    └─────────────────────────────────────────────────────┘   │",
            "│                                                             │",
            "│    ┌─────────────────────────────────────────────────────┐   │",
            "│    │               Monitoring (Port: 9090)               │   │",
            "│    │  • Prometheus Metrics  • Grafana Dashboards         │   │",
            "│    │  • Alert Manager  • Log Aggregation               │   │",
            "│    └─────────────────────────────────────────────────────┘   │",
            "│                                                             │",
            "└─────────────────────────────────────────────────────────────┘",
            "```"
        ]
        
        # Component table
        component_table = [
            "| Component | Type | Port | Description |",
            "|-----------|------|------|-------------|"
        ]
        
        for comp in components:
            component_table.append(f"| {comp['name']} | {comp['type']} | {comp['port']} | Core service component |")
        
        return f"""## Architecture Diagram

### System Components

{chr(10).join(component_table)}

### Data Flow

1. **Client Request**: Applications submit tasks via REST API
2. **Task Validation**: API Gateway validates and authenticates requests
3. **Task Scheduling**: Scheduler prioritizes and queues tasks in Redis
4. **Task Distribution**: Workers pull tasks from queue based on capacity
5. **Task Execution**: Workers execute tasks and report status
6. **Result Storage**: Results stored in PostgreSQL for retrieval
7. **Monitoring**: All components report metrics to monitoring system

### Deployment Options

- **Development**: Single-node with in-memory queue
- **Staging**: Multi-node with Redis and PostgreSQL
- **Production**: Kubernetes cluster with auto-scaling"""
    
    def _generate_quick_start(self):
        """Generate quick start guide."""
        return """## Quick Start Guide

### 1. Installation

```bash
# Create virtual environment
python3 -m venv venv
source venv/bin/activate  # Linux/macOS
# venv\Scripts\activate   # Windows

# Install dependencies
pip install -r requirements.txt

# Or install from PyPI
pip install distributed-task-queue
```

### 2. Configuration

Create a configuration file `config.yaml`:

```yaml
# config.yaml
server:
  host: "0.0.0.0"
  port: 8000
  workers: 4

queue:
  backend: "redis"
  redis_url: "redis://localhost:6379/0"
  max_retries: 3

database:
  url: "postgresql://user:password@localhost:5432/taskqueue"
  pool_size: 20

monitoring:
  enabled: true
  metrics_port: 9090
```

### 3. Start the Services

```bash
# Start Redis (if not running)
redis-server --daemonize yes

# Start PostgreSQL (create database)
createdb taskqueue

# Initialize database
python3 -m taskqueue.db.init

# Start the API server
python3 -m taskqueue.server --config config.yaml

# Start workers (in separate terminals)
python3 -m taskqueue.worker --config config.yaml --concurrency 4
```

### 4. Submit Your First Task

```python
from taskqueue import TaskQueueClient

# Initialize client
client = TaskQueueClient(base_url="http://localhost:8000")

# Submit a task
task_id = client.submit_task(
    name="process_data",
    payload={
        "data_url": "https://example.com/data.csv",
        "output_format": "json"
    },
    priority="high",
    timeout=300  # 5 minutes
)

print(f"Task submitted with ID: {task_id}")

# Check task status
status = client.get_task_status(task_id)
print(f"Task status: {status}")

# Get task result (when complete)
if status == "completed":
    result = client.get_task_result(task_id)
    print(f"Result: {result}")
```

### 5. Monitor Your Tasks

```bash
# View real-time metrics
curl http://localhost:9090/metrics

# Check queue status
curl http://localhost:8000/api/v1/queue/status

# View worker status
curl http://localhost:8000/api/v1/workers
```

### 6. Docker Deployment (Recommended)

```bash
# Clone the repository
git clone https://github.com/your-org/distributed-task-queue.git
cd distributed-task-queue

# Start all services with Docker Compose
docker-compose up -d

# Scale workers
docker-compose up -d --scale worker=4

# View logs
docker-compose logs -f
```"""
    
    def _generate_api_reference(self, api_endpoints):
        """Generate API reference section."""
        default_endpoints = [
            {
                "method": "POST",
                "path": "/api/v1/tasks",
                "description": "Submit a new task",
                "payload": {
                    "name": "string (required)",
                    "payload": "object (required)",
                    "priority": "string (low/medium/high/critical)",
                    "timeout": "integer (seconds)",
                    "retry_count": "integer (default: 3)"
                }
            },
            {
                "method": "GET",
                "path": "/api/v1/tasks/{task_id}",
                "description": "Get task details and status"
            },
            {
                "method": "GET",
                "path": "/api/v1/tasks/{task_id}/result",
                "description": "Get task result (when completed)"
            },
            {
                "method": "DELETE",
                "path": "/api/v1/tasks/{task_id}",
                "description": "Cancel a pending task"
            },
            {
                "method": "GET",
                "path": "/api/v1/queue/status",
                "description": "Get queue statistics and health"
            },
            {
                "method": "GET",
                "path": "/api/v1/workers",
                "description": "List all active workers and their status"
            }
        ]
        
        endpoints = api_endpoints or default_endpoints
        
        api_sections = []
        for endpoint in endpoints:
            api_sections.append(f"""#### {endpoint['method']} {endpoint['path']}

**Description:** {endpoint['description']}

{f"**Request Body:**```json\n{json.dumps(endpoint.get('payload', {}), indent=2)}\n```" if 'payload' in endpoint else ''}

**Response Codes:**
- `200`: Success
- `201`: Created
- `400`: Bad Request
- `404`: Not Found
- `500`: Internal Server Error""")
        
        return f"""## API Reference

### Base URL
```
http://localhost:8000/api/v1
```

### Authentication
All API requests require authentication via API key:
```bash
curl -H "X-API-Key: your_api_key_here" http://localhost:8000/api/v1/tasks
```

### Endpoints

{chr(10).join(api_sections)}

### Rate Limiting
- **Default:** 100 requests per minute per API key
- **Burst:** 10 requests per second
- **Headers:** `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`

### Error Response Format
```json
{
  "error": {
    "code": "INVALID_REQUEST",
    "message": "Task name is required",
    "details": {
      "field": "name",
      "issue": "missing_required_field"
    }
  },
  "request_id": "req_abc123",
  "timestamp": "2024-01-15T10:30:00Z"
}
```"""
    
    def _generate_configuration(self, config_options):
        """Generate configuration section."""
        default_options = [
            {
                "name": "server.host",
                "type": "string",
                "default": "0.0.0.0",
                "description": "Server bind address"
            },
            {
                "name": "server.port",
                "type": "integer",
                "default": 8000,
                "description": "Server port number"
            },
            {
                "name": "queue.backend",
                "type": "string",
                "default": "redis",
                "description": "Queue backend (redis, rabbitmq, kafka)"
            },
            {
                "name": "queue.redis_url",
                "type": "string",
                "default": "redis://localhost:6379/0",
                "description": "Redis connection URL"
            },
            {
                "name": "database.url",
                "type": "string",
                "default": "postgresql://localhost:5432/taskqueue",
                "description": "Database connection URL"
            },
            {
                "name": "worker.concurrency",
                "type": "integer",
                "default": 4,
                "description": "Number of concurrent tasks per worker"
            }
        ]
        
        options = config_options or default_options
        
        config_table = [
            "| Configuration Key | Type | Default | Description |",
            "|-------------------|------|---------|-------------|"
        ]
        
        for opt in options:
            default_val = str(opt['default'])
            if opt['type'] == 'string' and ('password' in opt['name'] or 'secret' in opt['name']):
                default_val = '***'
            config_table.append(f"| `{opt['name']}` | {opt['type']} | `{default_val}` | {opt['description']} |")
        
        return f"""## Configuration

### Environment Variables

| Variable | Description | Required |
|----------|-------------|----------|
| `TQ_SECRET_KEY` | JWT secret key for authentication | Yes |
| `TQ_REDIS_URL` | Redis connection URL | Yes |
| `TQ_DB_URL` | Database connection URL | Yes |
| `TQ_LOG_LEVEL` | Logging level (DEBUG, INFO, WARNING, ERROR) | No |

### Configuration File (`config.yaml`)

{chr(10).join(config_table)}

### Advanced Configuration

#### Redis Configuration
```yaml
queue:
  redis_url: "redis://:password@redis-host:6379/0"
  max_connections: 100
  socket_timeout: 5
  retry_on_timeout: true
```

#### Database Pool Configuration
```yaml
database:
  url: "postgresql://user:pass@localhost/dbname"
  pool_size: 20
  max_overflow: 10
  pool_timeout: 30
  pool_recycle: 1800
```

#### Worker Configuration
```yaml
worker:
  concurrency: 4
  prefetch_count: 10
  max_tasks_per_child: 1000
  task_timeout: 3600  # 1 hour
  graceful_shutdown_timeout: 300  # 5 minutes
```

#### Monitoring Configuration
```yaml
monitoring:
  enabled: true
  metrics_port: 9090
  health_check_path: "/health"
  log_format: "json"
  log_file: "/var/log/taskqueue/app.log"
```"""
    
    def _generate_footer(self):
        """Generate contributing and license sections."""
        return """## Contributing

We welcome contributions! Please see our [Contributing Guidelines](CONTRIBUTING.md) for details.

### Development Setup

```bash
# Clone the repository
git clone https://github.com/your-org/distributed-task-queue.git
cd distributed-task-queue

# Install development dependencies
pip install -e ".[dev]"

# Run tests
pytest

# Run linting
flake8 taskqueue/
black taskqueue/ --check
isort taskqueue/ --check-only
```

### Code Style
- Follow PEP 8 guidelines
- Use type hints for all function signatures
- Write docstrings for all public methods
- Maintain 90%+ test coverage

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

```
MIT License

Copyright (c) 2024 Your Organization

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

**Generated by AIOS Documentation Generator**  
**Last Updated:** {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}"""


# Main execution block for independent testing
if __name__ == "__main__":
    print("🚀 Documentation Generator Node Starting...", flush=True)
    
    # Sample project data for testing
    test_data = {
        "project_info": {
            "name": "Distributed Task Queue",
            "version": "2.1.0",
            "description": "A high-performance distributed task queue system for microservice architectures.",
            "features": [
                "Dynamic task prioritization",
                "Automatic failure recovery",
                "Real-time monitoring dashboard",
                "Horizontal scaling support",
                "Multiple queue backend support"
            ]
        },
        "components": [
            {"name": "API Server", "type": "service", "port": 8000},
            {"name": "Task Scheduler", "type": "core", "port": 8001},
            {"name": "Worker Nodes", "type": "compute", "port": "auto"},
            {"name": "Redis Cluster", "type": "storage", "port": 6379},
            {"name": "PostgreSQL", "type": "storage", "port": 5432},
            {"name": "Prometheus", "type": "monitoring", "port": 9090}
        ],
        "api_endpoints": [
            {
                "method": "POST",
                "path": "/api/v2/tasks",
                "description": "Submit a new task with enhanced options"
            },
            {
                "method": "GET",
                "path": "/api/v2/tasks/batch",
                "description": "Get multiple task statuses in batch"
            }
        ],
        "config_options": [
            {
                "name": "scheduler.algorithm",
                "type": "string",
                "default": "priority_round_robin",
                "description": "Task scheduling algorithm"
            },
            {
                "name": "worker.auto_scale",
                "type": "boolean",
                "default": true,
                "description": "Enable automatic worker scaling"
            }
        ]
    }
    
    # Create and run the documentation generator
    generator = DocumentationGenerator(
        agent_id="test_doc_generator",
        config={
            "project_name": "Distributed Task Queue",
            "project_version": "2.1.0"
        }
    )
    
    # Process the test data
    result = generator.process_data(test_data)
    
    # Print final result
    print(f"\n📊 GENERATION RESULT:", flush=True)
    print(f"Status: {result['status']}", flush=True)
    print(f"Output Path: {result.get('output_path', 'N/A')}", flush=True)
    print(f"Sections: {result.get('sections_count', 0)}", flush=True)
    
    if result['status'] == 'success':
        print(f"\n✅ DOCUMENTATION_GENERATED_SUCCESSFULLY", flush=True)
        print(f"📄 File size: {os.path.getsize(result['output_path'])} bytes", flush=True)
    else:
        print(f"\n❌ DOCUMENTATION_GENERATION_FAILED: {result.get('error', 'Unknown error')}", flush=True)
    
    print("\n🏁 Documentation Generator Node Completed.", flush=True)