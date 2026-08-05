import ast
import inspect
import sys
sys.path.insert(0, r'e:\ouisani\evaluation')
from run_tool_capability_classifier import make_bash_handler, classify_tool

src = inspect.getsource(make_bash_handler)
print("SOURCE:")
print(src)
print()

result = classify_tool(src, "bash")
print("CLASSIFY RESULT:", result)
