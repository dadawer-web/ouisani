# AIOS Tools Reference

## Core Tools

### bash
Execute shell commands in the working directory.
Input: {"command": "shell command"}
Returns: stdout + stderr

### file_read
Read file content from VFS.
Input: {"path": "/factory/file.py"}
Returns: file content

### file_write
Write content to VFS file.
Input: {"path": "/factory/file.py", "content": "file content"}
Returns: success/failure

### file_edit
Edit existing file with old_string → new_string replacement.
Input: {"path": "/factory/file.py", "old_string": "old", "new_string": "new"}
Returns: success/failure

### think
Call LLM for reasoning.
Input: {"prompt": "question"}
Returns: LLM response

## Dynamic Tools
Tools can be dynamically mounted via DynamicToolBridge based on query content.
