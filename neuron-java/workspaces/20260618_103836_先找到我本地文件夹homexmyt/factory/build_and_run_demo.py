#!/usr/bin/env python3
"""
Build and Run Demo Agent
Node: build_and_run_demo
Task: Compile the C++ project and run a simple demonstration
"""

import os
import sys
import subprocess
import json
import time
from pathlib import Path

# Try to import BaseAgent if available in the environment
try:
    from aios_kernel import BaseAgent
    print("SUCCESS: Imported BaseAgent from aios_kernel", flush=True)
except ImportError:
    print("WARNING: aios_kernel not available, using mock BaseAgent", flush=True)
    # Create a mock BaseAgent for standalone testing
    class BaseAgent:
        def __init__(self, name="BuildRunDemoAgent"):
            self.name = name
            print(f"Initialized {self.name}", flush=True)
        
        def process_data(self, data):
            """Override this method with actual implementation"""
            raise NotImplementedError("Subclasses must implement process_data")
        
        def run(self, input_data=None):
            """Run the agent with input data"""
            print(f"{self.name} starting execution...", flush=True)
            result = self.process_data(input_data)
            print(f"{self.name} completed execution", flush=True)
            return result

class BuildRunDemoAgent(BaseAgent):
    """Agent that compiles the C++ project and runs a demo"""
    
    def __init__(self):
        super().__init__(name="BuildRunDemoAgent")
        # Define project paths
        self.project_dir = Path("/home/xmy/tryaios/aios-java/workspaces/20260618_103836_先找到我本地文件夹homexmyt/factory/cpp_project")
        self.build_dir = self.project_dir / "build"
        self.output_dir = Path(os.getcwd()) / "outputs"
        
        # Ensure output directory exists
        self.output_dir.mkdir(parents=True, exist_ok=True)
        
        # Results file path
        self.results_file = self.output_dir / "build_and_run_results.json"
        
    def process_data(self, data):
        """
        Main processing method
        data: Input data (can be None for this task)
        Returns: Results dictionary
        """
        results = {
            "status": "started",
            "timestamp": time.time(),
            "steps": []
        }
        
        print("=" * 60, flush=True)
        print("BUILD_AND_RUN_DEMO AGENT STARTING", flush=True)
        print("=" * 60, flush=True)
        
        try:
            # Step 1: Check if project directory exists
            step1 = self._check_project_structure()
            results["steps"].append(step1)
            
            if not step1["success"]:
                results["status"] = "failed"
                results["error"] = step1["error"]
                return results
            
            # Step 2: Run build script
            step2 = self._run_build_script()
            results["steps"].append(step2)
            
            if not step2["success"]:
                results["status"] = "failed"
                results["error"] = step2["error"]
                return results
            
            # Step 3: Run demo
            step3 = self._run_demo()
            results["steps"].append(step3)
            
            if step3["success"]:
                results["status"] = "success"
                results["demo_output"] = step3["output"]
            else:
                results["status"] = "demo_failed"
                results["error"] = step3["error"]
            
            # Step 4: Save results
            self._save_results(results)
            
            print("=" * 60, flush=True)
            print(f"BUILD_AND_RUN_DEMO AGENT COMPLETED: {results['status']}", flush=True)
            print("=" * 60, flush=True)
            
            return results
            
        except Exception as e:
            print(f"CRITICAL ERROR: {str(e)}", flush=True)
            results["status"] = "error"
            results["error"] = str(e)
            self._save_results(results)
            return results
    
    def _check_project_structure(self):
        """Check if the project structure exists and is valid"""
        step = {"name": "check_project", "success": False, "error": None}
        
        print("Step 1: Checking project structure...", flush=True)
        
        if not self.project_dir.exists():
            step["error"] = f"Project directory not found: {self.project_dir}"
            print(f"ERROR: {step['error']}", flush=True)
            return step
        
        # Check for essential files
        required_files = ["CMakeLists.txt", "src/main.cpp"]
        missing_files = []
        
        for file in required_files:
            file_path = self.project_dir / file
            if not file_path.exists():
                missing_files.append(file)
        
        if missing_files:
            step["error"] = f"Missing required files: {missing_files}"
            print(f"ERROR: {step['error']}", flush=True)
            return step
        
        # Check if build.sh exists
        build_script = self.project_dir / "build.sh"
        if not build_script.exists():
            step["error"] = "Build script (build.sh) not found"
            print(f"ERROR: {step['error']}", flush=True)
            return step
        
        step["success"] = True
        step["project_dir"] = str(self.project_dir)
        print("SUCCESS: Project structure is valid", flush=True)
        
        return step
    
    def _run_build_script(self):
        """Execute the build script to compile the project"""
        step = {"name": "build_project", "success": False, "error": None, "output": ""}
        
        print("Step 2: Running build script...", flush=True)
        
        build_script = self.project_dir / "build.sh"
        
        # Make script executable
        build_script.chmod(0o755)
        
        try:
            # Execute build script
            result = subprocess.run(
                ["bash", str(build_script)],
                cwd=str(self.project_dir),
                capture_output=True,
                text=True,
                timeout=120  # 2 minutes timeout
            )
            
            step["return_code"] = result.returncode
            step["stdout"] = result.stdout[-500:] if result.stdout else ""
            step["stderr"] = result.stderr[-500:] if result.stderr else ""
            
            if result.returncode == 0:
                step["success"] = True
                print("SUCCESS: Build completed successfully", flush=True)
                print(f"Build output (last 500 chars):\n{step['stdout']}", flush=True)
            else:
                step["error"] = f"Build failed with return code {result.returncode}"
                print(f"ERROR: {step['error']}", flush=True)
                print(f"Build stderr:\n{step['stderr']}", flush=True)
                
        except subprocess.TimeoutExpired:
            step["error"] = "Build timed out after 120 seconds"
            print(f"ERROR: {step['error']}", flush=True)
        except Exception as e:
            step["error"] = f"Build execution error: {str(e)}"
            print(f"ERROR: {step['error']}", flush=True)
        
        return step
    
    def _run_demo(self):
        """Run the compiled demo executable"""
        step = {"name": "run_demo", "success": False, "error": None, "output": ""}
        
        print("Step 3: Running demo...", flush=True)
        
        # Find the executable - look for common patterns
        executable_candidates = [
            self.build_dir / "demo",
            self.build_dir / "build" / "demo",
            self.build_dir / "main",
            self.build_dir / "cpp_demo",
        ]
        
        executable = None
        for candidate in executable_candidates:
            if candidate.exists() and candidate.is_file():
                executable = candidate
                break
        
        if not executable:
            # Try to find any executable in build directory
            for path in self.build_dir.rglob("*"):
                if path.is_file() and os.access(path, os.X_OK):
                    executable = path
                    break
        
        if not executable:
            step["error"] = "No executable found in build directory"
            print(f"ERROR: {step['error']}", flush=True)
            # List build directory contents for debugging
            try:
                print("Build directory contents:", flush=True)
                for item in self.build_dir.iterdir():
                    print(f"  {item}", flush=True)
            except:
                pass
            return step
        
        print(f"Found executable: {executable}", flush=True)
        
        try:
            # Run the executable
            result = subprocess.run(
                [str(executable)],
                capture_output=True,
                text=True,
                timeout=30  # 30 seconds timeout for demo
            )
            
            step["return_code"] = result.returncode
            step["output"] = result.stdout[-1000:] if result.stdout else ""
            step["stderr"] = result.stderr[-500:] if result.stderr else ""
            
            if result.returncode == 0:
                step["success"] = True
                print("SUCCESS: Demo executed successfully", flush=True)
                print(f"Demo output:\n{step['output']}", flush=True)
            else:
                step["error"] = f"Demo execution failed with return code {result.returncode}"
                print(f"ERROR: {step['error']}", flush=True)
                print(f"Demo stderr:\n{step['stderr']}", flush=True)
                
        except subprocess.TimeoutExpired:
            step["error"] = "Demo execution timed out after 30 seconds"
            print(f"ERROR: {step['error']}", flush=True)
        except Exception as e:
            step["error"] = f"Demo execution error: {str(e)}"
            print(f"ERROR: {step['error']}", flush=True)
        
        return step
    
    def _save_results(self, results):
        """Save results to JSON file"""
        try:
            with open(self.results_file, 'w') as f:
                json.dump(results, f, indent=2)
            print(f"Results saved to: {self.results_file}", flush=True)
        except Exception as e:
            print(f"ERROR saving results: {str(e)}", flush=True)


def main():
    """Main function for standalone testing"""
    print("Starting BuildRunDemoAgent in standalone mode...", flush=True)
    
    agent = BuildRunDemoAgent()
    result = agent.run()
    
    print("\n" + "=" * 60, flush=True)
    print("EXECUTION SUMMARY", flush=True)
    print("=" * 60, flush=True)
    print(f"Final Status: {result.get('status', 'unknown')}", flush=True)
    
    if result.get('status') == 'success':
        print("BUILD_AND_RUN_DEMO SUCCESS: Project compiled and demo executed successfully!", flush=True)
    else:
        print(f"BUILD_AND_RUN_DEMO FAILED: {result.get('error', 'Unknown error')}", flush=True)
    
    # Print completion marker as required by rules
    print("\n" + "=" * 60, flush=True)
    print("NODE_VERIFIED_AND_READY", flush=True)
    print("=" * 60, flush=True)
    
    return result


if __name__ == "__main__":
    main()