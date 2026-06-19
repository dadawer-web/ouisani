#!/usr/bin/env python3
"""
Agent 1: Find Local Folder
Finds the local folder /home/xmy/tryaios/aios-java/workspaces/20260618_101813_开发一个支持高并发的分布式任务队列
and returns its contents and metadata.
"""

import os
import json
import sys
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
                raise

class FolderFinderAgent(BaseAgent):
    """Agent that finds and analyzes the target folder"""
    
    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        self.target_folder = "/home/xmy/tryaios/aios-java/workspaces/20260618_101813_开发一个支持高并发的分布式任务队列"
    
    def process_data(self, data):
        """Find the target folder and collect information about it"""
        print(f"[AGENT_1] Starting folder search for: {self.target_folder}", flush=True)
        
        # Check if target folder exists
        target_path = Path(self.target_folder)
        
        if not target_path.exists():
            print(f"[AGENT_1] ERROR: Target folder does not exist: {self.target_folder}", flush=True)
            return {
                "status": "error",
                "message": f"Target folder not found: {self.target_folder}",
                "exists": False
            }
        
        print(f"[AGENT_1] SUCCESS: Found target folder: {self.target_folder}", flush=True)
        
        # Collect folder information
        folder_info = {
            "status": "success",
            "target_folder": self.target_folder,
            "exists": True,
            "is_directory": target_path.is_dir(),
            "absolute_path": str(target_path.absolute()),
            "files": [],
            "subdirectories": [],
            "file_count": 0,
            "dir_count": 0,
            "total_size": 0
        }
        
        # List contents
        try:
            items = list(target_path.iterdir())
            
            for item in items:
                item_info = {
                    "name": item.name,
                    "path": str(item),
                    "is_file": item.is_file(),
                    "is_dir": item.is_dir(),
                    "size": 0
                }
                
                if item.is_file():
                    try:
                        item_info["size"] = item.stat().st_size
                        folder_info["total_size"] += item_info["size"]
                        folder_info["files"].append(item_info)
                        folder_info["file_count"] += 1
                    except (OSError, PermissionError) as e:
                        print(f"[AGENT_1] WARNING: Could not get size for {item.name}: {e}", flush=True)
                        folder_info["files"].append(item_info)
                        folder_info["file_count"] += 1
                
                elif item.is_dir():
                    folder_info["subdirectories"].append(item_info)
                    folder_info["dir_count"] += 1
            
            print(f"[AGENT_1] Found {folder_info['file_count']} files and {folder_info['dir_count']} directories", flush=True)
            
        except PermissionError as e:
            print(f"[AGENT_1] WARNING: Permission error accessing folder: {e}", flush=True)
            folder_info["permission_error"] = str(e)
        
        except Exception as e:
            print(f"[AGENT_1] ERROR: Failed to list folder contents: {e}", flush=True)
            folder_info["list_error"] = str(e)
        
        # Convert total size to human-readable format
        if folder_info["total_size"] > 0:
            size_bytes = folder_info["total_size"]
            if size_bytes < 1024:
                size_str = f"{size_bytes} B"
            elif size_bytes < 1024 * 1024:
                size_str = f"{size_bytes / 1024:.2f} KB"
            elif size_bytes < 1024 * 1024 * 1024:
                size_str = f"{size_bytes / (1024 * 1024):.2f} MB"
            else:
                size_str = f"{size_bytes / (1024 * 1024 * 1024):.2f} GB"
            
            folder_info["total_size_human"] = size_str
        
        print(f"[AGENT_1] Folder analysis complete. Total size: {folder_info.get('total_size_human', '0 B')}", flush=True)
        
        return folder_info

def main():
    """Main entry point for standalone testing"""
    print("=" * 60, flush=True)
    print("AGENT_1: Starting folder finder agent", flush=True)
    print("=" * 60, flush=True)
    
    try:
        # Create and run the agent
        agent = FolderFinderAgent(agent_id="agent_1")
        result = agent.run()
        
        # Save results to file - use current working directory's factory/outputs
        try:
            # Try the standard path first (for VFS environment)
            output_dir = "/factory/outputs"
            os.makedirs(output_dir, exist_ok=True)
        except PermissionError:
            # Fallback to current working directory for physical machine
            current_dir = os.getcwd()
            output_dir = os.path.join(current_dir, "factory", "outputs")
            os.makedirs(output_dir, exist_ok=True)
        
        output_file = os.path.join(output_dir, "agent_1_result.json")
        with open(output_file, 'w', encoding='utf-8') as f:
            json.dump(result, f, indent=2, ensure_ascii=False)
        
        print(f"[AGENT_1] Results saved to: {output_file}", flush=True)
        print(f"[AGENT_1] Final result status: {result.get('status', 'unknown')}", flush=True)
        
        if result.get('exists', False):
            print("AGENT_1_SUCCESS: Folder found and analyzed!", flush=True)
        else:
            print("AGENT_1_COMPLETE: Folder analysis complete (folder not found)", flush=True)
        
        return 0
        
    except Exception as e:
        print(f"AGENT_1_ERROR: {e}", flush=True)
        import traceback
        traceback.print_exc()
        return 1

if __name__ == "__main__":
    sys.exit(main())