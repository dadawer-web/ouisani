import uvicorn
from fastapi import FastAPI
from pydantic import BaseModel
import multiprocessing
import sys
import io
import contextlib
import resource
import os

app = FastAPI(title="eruitah-sandbox API")

SANDBOX_TIMEOUT = 15


class ExecuteRequest(BaseModel):
    code: str


def _run_code(code: str, result_dict: dict):
    MB = 1024 * 1024
    try:
        resource.setrlimit(resource.RLIMIT_AS, (256 * MB, 256 * MB))
        resource.setrlimit(resource.RLIMIT_CPU, (10, 10))
        resource.setrlimit(resource.RLIMIT_FSIZE, (10 * MB, 10 * MB))
        resource.setrlimit(resource.RLIMIT_NPROC, (0, 0))
    except Exception as e:
        result_dict["stderr"] = f"[Kernel Fault] Failed to set sandbox limits: {e}"
        return

    stdout_buffer = io.StringIO()
    stderr_buffer = io.StringIO()
    try:
        with contextlib.redirect_stdout(stdout_buffer), contextlib.redirect_stderr(stderr_buffer):
            exec(code, {})
    except MemoryError:
        stderr_buffer.write("\n[Segfault] 内存溢出！沙箱已被操作系统强杀。\n")
    except Exception as e:
        stderr_buffer.write(str(e))
    result_dict["stdout"] = stdout_buffer.getvalue()
    result_dict["stderr"] = stderr_buffer.getvalue()


@app.post("/execute")
async def execute_code(req: ExecuteRequest):
    print(f"[Sandbox API] Received execution request:\n{req.code}")

    manager = multiprocessing.Manager()
    result_dict = manager.dict()

    proc = multiprocessing.Process(target=_run_code, args=(req.code, result_dict))
    proc.start()
    proc.join(timeout=SANDBOX_TIMEOUT)

    if proc.is_alive():
        print(f"[Sandbox API] TIMEOUT! Killing process (pid={proc.pid})")
        proc.terminate()
        proc.join(timeout=2)
        if proc.is_alive():
            proc.kill()
            proc.join()
        return {
            "stdout": "",
            "stderr": f"[TIMEOUT] Execution exceeded {SANDBOX_TIMEOUT}s limit and was killed",
        }

    stdout_val = result_dict.get("stdout", "")
    stderr_val = result_dict.get("stderr", "")

    print(f"[Sandbox API] Execution complete: stdout={len(stdout_val)}B, stderr={len(stderr_val)}B")
    return {"stdout": stdout_val, "stderr": stderr_val}


if __name__ == "__main__":
    multiprocessing.set_start_method("spawn", force=True)
    uvicorn.run(app, host="127.0.0.1", port=5000)
