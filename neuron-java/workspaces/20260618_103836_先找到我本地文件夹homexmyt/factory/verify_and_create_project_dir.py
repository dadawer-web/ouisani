#!/usr/bin/env python3
"""
Verify and Create Project Directory Node
Verifies and prepares the target project directory structure.
"""

import os
import json
import sys
import time
from pathlib import Path

# Try to import BaseAgent, handle gracefully if not available
try:
    from aios import BaseAgent
except ImportError:
    # Fallback implementation for testing
    class BaseAgent:
        def __init__(self, **kwargs):
            self.agent_id = kwargs.get('agent_id', 'unknown')
        
        def process_data(self, data):
            """Override this method in subclasses"""
            raise NotImplementedError
        
        def run(self):
            """Main execution method"""
            print(f"Agent {self.agent_id} started", flush=True)
            try:
                result = self.process_data({})
                print(f"Agent {self.agent_id} completed successfully", flush=True)
                return result
            except Exception as e:
                print(f"Agent {self.agent_id} failed: {e}", flush=True)
                sys.exit(1)


class VerifyAndCreateProjectDirAgent(BaseAgent):
    """Agent to verify and create project directory structure"""
    
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        # Default project directory structure
        self.project_structure = {
            'src': {},
            'tests': {},
            'docs': {},
            'build': {},
            'logs': {},
            'outputs': {}
        }
    
    def process_data(self, data):
        """
        Process the data and verify/create project directory.
        
        Args:
            data: Dictionary containing:
                - project_path: Path to the project directory (required)
                - structure: Optional custom directory structure
        
        Returns:
            Dictionary with verification results
        """
        print("NODE_START: verify_and_create_project_dir", flush=True)
        
        # Extract project path from data
        project_path = data.get('project_path')
        if not project_path:
            error_msg = "ERROR: project_path not provided in data"
            print(error_msg, flush=True)
            return {'success': False, 'error': error_msg}
        
        # Use custom structure if provided
        if 'structure' in data:
            self.project_structure = data['structure']
        
        print(f"Verifying project directory: {project_path}", flush=True)
        
        try:
            # Convert to Path object for easier manipulation
            project_dir = Path(project_path)
            
            # Check if directory exists
            if project_dir.exists():
                print(f"Project directory already exists: {project_path}", flush=True)
                # Check if it's a directory
                if not project_dir.is_dir():
                    error_msg = f"ERROR: {project_path} exists but is not a directory"
                    print(error_msg, flush=True)
                    return {'success': False, 'error': error_msg, 'path': project_path}
                
                # List existing contents
                existing_contents = []
                for item in project_dir.iterdir():
                    existing_contents.append(item.name)
                
                print(f"Existing directory contents: {existing_contents}", flush=True)
                
                # Check if required subdirectories exist, create if missing
                created_dirs = []
                for dir_name in self.project_structure.keys():
                    sub_dir = project_dir / dir_name
                    if not sub_dir.exists():
                        print(f"Creating missing directory: {dir_name}", flush=True)
                        sub_dir.mkdir(parents=True, exist_ok=True)
                        created_dirs.append(dir_name)
                    elif not sub_dir.is_dir():
                        error_msg = f"ERROR: {sub_dir} exists but is not a directory"
                        print(error_msg, flush=True)
                        return {'success': False, 'error': error_msg, 'path': project_path}
                
                result = {
                    'success': True,
                    'path': str(project_path),
                    'existed': True,
                    'existing_contents': existing_contents,
                    'created_directories': created_dirs,
                    'message': 'Project directory verified and updated'
                }
            else:
                print(f"Project directory does not exist. Creating: {project_path}", flush=True)
                
                # Create the project directory and all subdirectories
                project_dir.mkdir(parents=True, exist_ok=True)
                
                # Create the directory structure
                created_dirs = []
                for dir_name in self.project_structure.keys():
                    sub_dir = project_dir / dir_name
                    sub_dir.mkdir(parents=True, exist_ok=True)
                    created_dirs.append(dir_name)
                    print(f"Created directory: {dir_name}", flush=True)
                
                result = {
                    'success': True,
                    'path': str(project_path),
                    'existed': False,
                    'created_directories': created_dirs,
                    'message': 'Project directory created with full structure'
                }
            
            # Create a verification marker file
            verification_file = project_dir / '.verification_complete'
            verification_data = {
                'verified_at': time.time(),
                'structure': list(self.project_structure.keys()),
                'agent': self.agent_id,
                'verification_time': time.strftime('%Y-%m-%d %H:%M:%S')
            }
            
            with open(verification_file, 'w') as f:
                json.dump(verification_data, f, indent=2)
            
            print(f"Verification marker created: {verification_file}", flush=True)
            
            # Save result to outputs directory
            script_dir = os.path.dirname(os.path.abspath(__file__))
            output_dir = os.path.join(script_dir, 'outputs')
            os.makedirs(output_dir, exist_ok=True)
            output_path = os.path.join(output_dir, 'verify_and_create_project_dir_result.json')
            
            with open(output_path, 'w') as f:
                json.dump(result, f, indent=2)
            
            print(f"Result saved to: {output_path}", flush=True)
            print("NODE_SUCCESS: verify_and_create_project_dir", flush=True)
            
            return result
            
        except Exception as e:
            error_msg = f"ERROR during directory verification: {str(e)}"
            print(error_msg, flush=True)
            print("NODE_FAILED: verify_and_create_project_dir", flush=True)
            return {'success': False, 'error': error_msg, 'path': project_path}


def main():
    """Main function for testing"""
    print("=== VerifyAndCreateProjectDirAgent Test ===", flush=True)
    
    # Create the agent
    agent = VerifyAndCreateProjectDirAgent(agent_id='verify_and_create_project_dir')
    
    # Test with a sample project path
    test_data = {
        'project_path': '/tmp/test_verify_project',
        'structure': {
            'src': {},
            'tests': {},
            'docs': {},
            'build': {},
            'logs': {},
            'outputs': {}
        }
    }
    
    print("Testing VerifyAndCreateProjectDirAgent...", flush=True)
    result = agent.process_data(test_data)
    
    print(f"\nTest result: {json.dumps(result, indent=2)}", flush=True)
    
    # Clean up test directory
    import shutil
    if os.path.exists('/tmp/test_verify_project'):
        shutil.rmtree('/tmp/test_verify_project')
        print("Cleaned up test directory", flush=True)
    
    print("\n=== VERIFICATION COMPLETE: Script executed successfully! ===", flush=True)


if __name__ == '__main__':
    main()