#!/usr/bin/env python3
"""
Task State Manager - 实现任务状态管理器，支持状态流转、任务追踪、超时检测

This module provides a comprehensive task state management system with:
- Task state transitions with validation
- Task tracking and history
- Timeout detection and handling
- Thread-safe operations
"""

import json
import time
import threading
import logging
from datetime import datetime, timedelta
from enum import Enum, auto
from typing import Dict, List, Optional, Any, Callable
from dataclasses import dataclass, asdict, field
from pathlib import Path

# Configure logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s',
    handlers=[
        logging.StreamHandler()
    ]
)
logger = logging.getLogger(__name__)

class TaskState(Enum):
    """Possible states for a task."""
    PENDING = auto()
    QUEUED = auto()
    RUNNING = auto()
    PAUSED = auto()
    COMPLETED = auto()
    FAILED = auto()
    CANCELLED = auto()
    TIMEOUT = auto()

class StateTransitionError(Exception):
    """Exception raised for invalid state transitions."""
    pass

@dataclass
class TaskRecord:
    """Data class representing a task record."""
    task_id: str
    name: str
    state: TaskState
    created_at: datetime
    updated_at: datetime
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    timeout_seconds: Optional[int] = None
    owner: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)
    history: List[Dict[str, Any]] = field(default_factory=list)
    error_message: Optional[str] = None

    def to_dict(self) -> Dict[str, Any]:
        """Convert task record to dictionary for serialization."""
        data = asdict(self)
        # Convert datetime objects to ISO format strings
        for key in ['created_at', 'updated_at', 'started_at', 'completed_at']:
            if data[key] is not None:
                data[key] = data[key].isoformat()
        # Convert enum to string
        data['state'] = self.state.name
        return data

class TaskStateManager:
    """
    Manages task states with support for:
    - State transitions
    - Task tracking
    - Timeout detection
    - Thread-safe operations
    """
    
    # Define valid state transitions
    STATE_TRANSITIONS = {
        TaskState.PENDING: [TaskState.QUEUED, TaskState.CANCELLED],
        TaskState.QUEUED: [TaskState.RUNNING, TaskState.CANCELLED],
        TaskState.RUNNING: [TaskState.PAUSED, TaskState.COMPLETED, TaskState.FAILED, TaskState.TIMEOUT, TaskState.CANCELLED],
        TaskState.PAUSED: [TaskState.RUNNING, TaskState.CANCELLED],
        TaskState.COMPLETED: [],  # Terminal state
        TaskState.FAILED: [],  # Terminal state
        TaskState.CANCELLED: [],  # Terminal state
        TaskState.TIMEOUT: [TaskState.RUNNING, TaskState.CANCELLED],  # Can retry or cancel after timeout
    }
    
    def __init__(self, storage_path: str = None):
        """
        Initialize the TaskStateManager.
        
        Args:
            storage_path: Path to persist task states. Defaults to outputs/task_states.json in current directory.
        """
        self.tasks: Dict[str, TaskRecord] = {}
        if storage_path is None:
            # Use outputs directory relative to current working directory
            storage_path = str(Path.cwd() / "outputs" / "task_states.json")
        self.storage_path = Path(storage_path)
        self.lock = threading.RLock()
        self.timeout_check_interval = 10  # seconds
        self.timeout_checker_running = False
        self.timeout_checker_thread = None
        
        # Create storage directory if it doesn't exist
        self.storage_path.parent.mkdir(parents=True, exist_ok=True)
        
        # Load existing tasks if storage file exists
        self._load_tasks()
        
        logger.info(f"TaskStateManager initialized with storage at {self.storage_path}")
    
    def _load_tasks(self) -> None:
        """Load tasks from persistent storage."""
        try:
            if self.storage_path.exists():
                with open(self.storage_path, 'r') as f:
                    data = json.load(f)
                    for task_id, task_data in data.items():
                        # Convert ISO strings back to datetime objects
                        for key in ['created_at', 'updated_at', 'started_at', 'completed_at']:
                            if task_data.get(key) is not None:
                                task_data[key] = datetime.fromisoformat(task_data[key])
                        # Convert state string back to enum
                        if 'state' in task_data:
                            task_data['state'] = TaskState[task_data['state']]
                        self.tasks[task_id] = TaskRecord(**task_data)
                logger.info(f"Loaded {len(self.tasks)} tasks from storage")
        except Exception as e:
            logger.error(f"Error loading tasks from storage: {e}")
            # Start with empty tasks if loading fails
            self.tasks = {}
    
    def _save_tasks(self) -> None:
        """Save tasks to persistent storage."""
        try:
            with open(self.storage_path, 'w') as f:
                # Convert tasks to serializable format
                serializable_tasks = {}
                for task_id, task in self.tasks.items():
                    serializable_tasks[task_id] = task.to_dict()
                json.dump(serializable_tasks, f, indent=2)
            logger.debug(f"Saved {len(self.tasks)} tasks to storage")
        except Exception as e:
            logger.error(f"Error saving tasks to storage: {e}")
    
    def create_task(self, 
                   task_id: str, 
                   name: str, 
                   owner: Optional[str] = None,
                   timeout_seconds: Optional[int] = None,
                   metadata: Optional[Dict[str, Any]] = None) -> TaskRecord:
        """
        Create a new task.
        
        Args:
            task_id: Unique identifier for the task
            name: Human-readable task name
            owner: Task owner (optional)
            timeout_seconds: Timeout in seconds (optional)
            metadata: Additional task metadata (optional)
            
        Returns:
            TaskRecord: The created task record
            
        Raises:
            ValueError: If task_id already exists
        """
        with self.lock:
            if task_id in self.tasks:
                raise ValueError(f"Task with ID {task_id} already exists")
            
            now = datetime.now()
            task = TaskRecord(
                task_id=task_id,
                name=name,
                state=TaskState.PENDING,
                created_at=now,
                updated_at=now,
                owner=owner,
                timeout_seconds=timeout_seconds,
                metadata=metadata or {},
                history=[{
                    'timestamp': now.isoformat(),
                    'action': 'CREATED',
                    'state': TaskState.PENDING.name,
                    'message': f"Task '{name}' created"
                }]
            )
            
            self.tasks[task_id] = task
            self._save_tasks()
            
            logger.info(f"Created task: {task_id} - {name}")
            return task
    
    def transition_task(self, 
                       task_id: str, 
                       new_state: TaskState, 
                       message: Optional[str] = None) -> TaskRecord:
        """
        Transition a task to a new state.
        
        Args:
            task_id: ID of the task to transition
            new_state: Target state
            message: Optional message describing the transition
            
        Returns:
            TaskRecord: The updated task record
            
        Raises:
            ValueError: If task_id does not exist
            StateTransitionError: If transition is not valid
        """
        with self.lock:
            if task_id not in self.tasks:
                raise ValueError(f"Task with ID {task_id} does not exist")
            
            task = self.tasks[task_id]
            current_state = task.state
            
            # Check if transition is valid
            if new_state not in self.STATE_TRANSITIONS.get(current_state, []):
                raise StateTransitionError(
                    f"Invalid transition: {current_state.name} -> {new_state.name}"
                )
            
            # Update task state
            now = datetime.now()
            task.state = new_state
            task.updated_at = now
            
            # Update timestamps based on new state
            if new_state == TaskState.RUNNING and task.started_at is None:
                task.started_at = now
            elif new_state in [TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED]:
                task.completed_at = now
            
            # Set error message if provided
            if new_state == TaskState.FAILED and message:
                task.error_message = message
            
            # Add to history
            history_entry = {
                'timestamp': now.isoformat(),
                'action': 'STATE_TRANSITION',
                'from_state': current_state.name,
                'to_state': new_state.name,
                'message': message or f"Transitioned from {current_state.name} to {new_state.name}"
            }
            task.history.append(history_entry)
            
            self._save_tasks()
            
            logger.info(f"Transitioned task {task_id}: {current_state.name} -> {new_state.name}")
            return task
    
    def get_task(self, task_id: str) -> Optional[TaskRecord]:
        """
        Get a task by ID.
        
        Args:
            task_id: ID of the task to retrieve
            
        Returns:
            TaskRecord or None: The task record if found, None otherwise
        """
        with self.lock:
            return self.tasks.get(task_id)
    
    def get_all_tasks(self) -> List[TaskRecord]:
        """
        Get all tasks.
        
        Returns:
            List[TaskRecord]: List of all task records
        """
        with self.lock:
            return list(self.tasks.values())
    
    def get_tasks_by_state(self, state: TaskState) -> List[TaskRecord]:
        """
        Get all tasks in a specific state.
        
        Args:
            state: State to filter by
            
        Returns:
            List[TaskRecord]: List of tasks in the specified state
        """
        with self.lock:
            return [task for task in self.tasks.values() if task.state == state]
    
    def update_task_metadata(self, 
                            task_id: str, 
                            metadata: Dict[str, Any]) -> TaskRecord:
        """
        Update task metadata.
        
        Args:
            task_id: ID of the task to update
            metadata: New metadata to merge with existing
            
        Returns:
            TaskRecord: The updated task record
            
        Raises:
            ValueError: If task_id does not exist
        """
        with self.lock:
            if task_id not in self.tasks:
                raise ValueError(f"Task with ID {task_id} does not exist")
            
            task = self.tasks[task_id]
            task.metadata.update(metadata)
            task.updated_at = datetime.now()
            
            # Add to history
            history_entry = {
                'timestamp': datetime.now().isoformat(),
                'action': 'METADATA_UPDATE',
                'message': f"Updated metadata keys: {list(metadata.keys())}"
            }
            task.history.append(history_entry)
            
            self._save_tasks()
            
            logger.info(f"Updated metadata for task {task_id}")
            return task
    
    def check_timeouts(self) -> List[str]:
        """
        Check for timed-out tasks and transition them to TIMEOUT state.
        
        Returns:
            List[str]: List of task IDs that were timed out
        """
        with self.lock:
            timed_out_tasks = []
            now = datetime.now()
            
            for task_id, task in list(self.tasks.items()):
                if (task.state == TaskState.RUNNING and 
                    task.timeout_seconds is not None and 
                    task.started_at is not None):
                    
                    # Calculate if task has timed out
                    elapsed_seconds = (now - task.started_at).total_seconds()
                    if elapsed_seconds > task.timeout_seconds:
                        try:
                            self.transition_task(
                                task_id, 
                                TaskState.TIMEOUT,
                                f"Task timed out after {elapsed_seconds:.2f} seconds"
                            )
                            timed_out_tasks.append(task_id)
                            logger.warning(f"Task {task_id} timed out after {elapsed_seconds:.2f} seconds")
                        except Exception as e:
                            logger.error(f"Error transitioning timed-out task {task_id}: {e}")
            
            return timed_out_tasks
    
    def start_timeout_checker(self, interval: int = 10) -> None:
        """
        Start a background thread to periodically check for timeouts.
        
        Args:
            interval: Check interval in seconds (default: 10)
        """
        if self.timeout_checker_running:
            logger.warning("Timeout checker is already running")
            return
        
        self.timeout_check_interval = interval
        self.timeout_checker_running = True
        
        def timeout_checker():
            while self.timeout_checker_running:
                try:
                    self.check_timeouts()
                except Exception as e:
                    logger.error(f"Error in timeout checker: {e}")
                time.sleep(self.timeout_check_interval)
        
        self.timeout_checker_thread = threading.Thread(
            target=timeout_checker, 
            daemon=True,
            name="task-timeout-checker"
        )
        self.timeout_checker_thread.start()
        logger.info(f"Started timeout checker with interval {interval} seconds")
    
    def stop_timeout_checker(self) -> None:
        """Stop the background timeout checker thread."""
        if not self.timeout_checker_running:
            return
        
        self.timeout_checker_running = False
        if self.timeout_checker_thread:
            self.timeout_checker_thread.join(timeout=5)
            self.timeout_checker_thread = None
        logger.info("Stopped timeout checker")
    
    def get_task_statistics(self) -> Dict[str, Any]:
        """
        Get statistics about all tasks.
        
        Returns:
            Dict: Statistics including counts by state, average execution time, etc.
        """
        with self.lock:
            stats = {
                'total_tasks': len(self.tasks),
                'by_state': {},
                'average_execution_time': None,
                'timeout_rate': None
            }
            
            # Count tasks by state
            for state in TaskState:
                stats['by_state'][state.name] = len(self.get_tasks_by_state(state))
            
            # Calculate average execution time for completed tasks
            completed_tasks = [t for t in self.tasks.values() 
                             if t.state == TaskState.COMPLETED and 
                             t.started_at is not None and 
                             t.completed_at is not None]
            
            if completed_tasks:
                total_execution_time = sum(
                    (t.completed_at - t.started_at).total_seconds() 
                    for t in completed_tasks
                )
                stats['average_execution_time'] = total_execution_time / len(completed_tasks)
            
            # Calculate timeout rate
            timed_out_tasks = len(self.get_tasks_by_state(TaskState.TIMEOUT))
            running_tasks = len(self.get_tasks_by_state(TaskState.RUNNING))
            if running_tasks + timed_out_tasks > 0:
                stats['timeout_rate'] = timed_out_tasks / (running_tasks + timed_out_tasks) * 100
            
            return stats


# For backward compatibility and easy import
StateManager = TaskStateManager


# Example usage and test function
def test_state_manager():
    """Test the TaskStateManager functionality."""
    print("=== Testing TaskStateManager ===")
    
    # Create a state manager
    manager = TaskStateManager()
    
    # Create some tasks
    try:
        task1 = manager.create_task(
            task_id="task-001",
            name="Process data",
            timeout_seconds=30
        )
        print(f"Created task: {task1.task_id} - {task1.name}")
        
        task2 = manager.create_task(
            task_id="task-002",
            name="Generate report",
            timeout_seconds=60
        )
        print(f"Created task: {task2.task_id} - {task2.name}")
        
        # Transition tasks through states
        manager.transition_task("task-001", TaskState.QUEUED)
        manager.transition_task("task-001", TaskState.RUNNING)
        manager.transition_task("task-002", TaskState.QUEUED)
        manager.transition_task("task-002", TaskState.RUNNING)
        
        # Update metadata
        manager.update_task_metadata("task-001", {"priority": "high", "retries": 0})
        
        # Get task details
        task1 = manager.get_task("task-001")
        print(f"Task 1 state: {task1.state.name}")
        print(f"Task 1 metadata: {task1.metadata}")
        
        # Get statistics
        stats = manager.get_task_statistics()
        print(f"Task statistics: {stats}")
        
        # Test invalid transition (should raise exception)
        try:
            manager.transition_task("task-001", TaskState.PENDING)
        except StateTransitionError as e:
            print(f"Expected error caught: {e}")
        
        print("\n=== TaskStateManager test completed successfully ===")
        
    except Exception as e:
        print(f"Error during test: {e}")
        import traceback
        traceback.print_exc()


if __name__ == "__main__":
    test_state_manager()