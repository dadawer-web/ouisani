import { useState, useCallback, useRef } from "react";
import { Upload, FileText, Download, FolderTree, X, Loader2, CheckCircle2, AlertCircle } from "lucide-react";
import { AIOS_API_URL } from "@/config";

const AIOS_TOKEN = "AIOS-SUPER-SECRET-KEY";

interface VfsFile {
  path: string;
  name: string;
}

/** VFS 工作空间桥接器 — 前端与 VFS 双向打通（借鉴 Apboa 工作空间） */
export default function VfsWorkspaceBridge() {
  const [isDragging, setIsDragging] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [uploadResult, setUploadResult] = useState<string | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);

  // 文件浏览状态
  const [browsePath, setBrowsePath] = useState("/vfs/workspace");
  const [files, setFiles] = useState<VfsFile[]>([]);
  const [browsing, setBrowsing] = useState(false);
  const [previewContent, setPreviewContent] = useState<string | null>(null);
  const [previewPath, setPreviewPath] = useState<string | null>(null);
  const [loadingPreview, setLoadingPreview] = useState(false);

  // 任务 ID — 用于隔离不同任务的文件
  const [taskId, setTaskId] = useState("default");

  const fileInputRef = useRef<HTMLInputElement>(null);

  // ── 拖拽上传 ──
  const handleDragOver = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(true);
  }, []);

  const handleDragLeave = useCallback((e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);
  }, []);

  const handleDrop = useCallback(async (e: React.DragEvent) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragging(false);

    const droppedFiles = Array.from(e.dataTransfer.files);
    if (droppedFiles.length === 0) return;

    await uploadFiles(droppedFiles);
  }, [taskId]);

  // ── 文件选择上传 ──
  const handleFileSelect = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const selected = Array.from(e.target.files || []);
    if (selected.length === 0) return;
    await uploadFiles(selected);
    // 清空 input 以便重复上传同一文件
    if (fileInputRef.current) fileInputRef.current.value = "";
  }, [taskId]);

  // ── 上传文件到后端 ──
  const uploadFiles = async (fileList: File[]) => {
    setUploading(true);
    setUploadError(null);
    setUploadResult(null);

    let totalFiles = 0;
    let vfsPath = "";

    try {
      for (const file of fileList) {
        const formData = new FormData();
        formData.append("file", file);

        const isZip = file.name.toLowerCase().endsWith(".zip");
        const url = `${AIOS_API_URL}/api/vfs/upload?taskId=${encodeURIComponent(taskId)}&token=${AIOS_TOKEN}${!isZip ? `&filename=${encodeURIComponent(file.name)}` : ""}`;

        const response = await fetch(url, {
          method: "POST",
          body: file, // 直接发送文件二进制
        });

        if (!response.ok) {
          const errText = await response.text();
          throw new Error(`上传失败 (${response.status}): ${errText}`);
        }

        const result = await response.json();
        totalFiles += result.fileCount || 0;
        vfsPath = result.vfsPath || "";
      }

      setUploadResult(`成功上传 ${totalFiles} 个文件到 ${vfsPath}`);
      // 自动刷新文件列表
      setBrowsePath(vfsPath || `/vfs/workspace/${taskId}`);
      await browseVfs(vfsPath || `/vfs/workspace/${taskId}`);
    } catch (e) {
      setUploadError(e instanceof Error ? e.message : String(e));
    } finally {
      setUploading(false);
    }
  };

  // ── 浏览 VFS 目录 ──
  const browseVfs = async (path: string) => {
    setBrowsing(true);
    try {
      const response = await fetch(
        `${AIOS_API_URL}/api/vfs/list?path=${encodeURIComponent(path)}&token=${AIOS_TOKEN}`
      );
      if (response.ok) {
        const fileList: string[] = await response.json();
        const vfsFiles: VfsFile[] = fileList.map((p) => ({
          path: p,
          name: p.substring(p.lastIndexOf("/") + 1),
        }));
        setFiles(vfsFiles);
      } else {
        setFiles([]);
      }
    } catch {
      setFiles([]);
    } finally {
      setBrowsing(false);
    }
  };

  // ── 预览文件 ──
  const previewFile = async (path: string) => {
    setLoadingPreview(true);
    setPreviewPath(path);
    setPreviewContent(null);
    try {
      const response = await fetch(
        `${AIOS_API_URL}/api/vfs/read?path=${encodeURIComponent(path)}&token=${AIOS_TOKEN}`
      );
      if (response.ok) {
        const text = await response.text();
        setPreviewContent(text);
      } else {
        setPreviewContent(`// 无法读取文件: ${response.status}`);
      }
    } catch {
      setPreviewContent("// 后端不可达");
    } finally {
      setLoadingPreview(false);
    }
  };

  // ── 下载文件 ──
  const downloadFile = (path: string) => {
    const url = `${AIOS_API_URL}/api/vfs/download?path=${encodeURIComponent(path)}&token=${AIOS_TOKEN}`;
    window.open(url, "_blank");
  };

  return (
    <div className="flex flex-col gap-3">
      {/* ── 任务 ID 输入 ── */}
      <div>
        <label className="mb-1 block text-[10px] font-medium tracking-wider text-cyan-400/80 uppercase">
          Task ID
        </label>
        <input
          type="text"
          value={taskId}
          onChange={(e) => setTaskId(e.target.value)}
          placeholder="任务 ID（用于隔离文件）"
          className="w-full rounded border border-zinc-700/50 bg-zinc-900/50 px-2 py-1.5 text-xs text-zinc-200 outline-none transition-colors focus:border-cyan-500/50"
        />
      </div>

      {/* ── 拖拽上传区 ── */}
      <div
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
        onClick={() => fileInputRef.current?.click()}
        className={`relative flex cursor-pointer flex-col items-center justify-center rounded-lg border-2 border-dashed p-6 transition-all duration-300 ${
          isDragging
            ? "border-cyan-400 bg-cyan-500/10 shadow-[0_0_20px_rgba(0,240,255,0.2)]"
            : "border-zinc-700/50 bg-zinc-900/30 hover:border-cyan-600/50 hover:bg-zinc-900/50"
        }`}
      >
        <input
          ref={fileInputRef}
          type="file"
          multiple
          onChange={handleFileSelect}
          className="hidden"
          accept=".zip,.py,.js,.ts,.java,.json,.md,.txt,.html,.css,.xml,.yml,.yaml,.go,.rs,.c,.cpp,.h"
        />

        {uploading ? (
          <>
            <Loader2 className="h-8 w-8 animate-spin text-cyan-400" />
            <span className="mt-2 text-xs text-cyan-300">正在上传并解压...</span>
          </>
        ) : (
          <>
            <Upload
              className={`h-8 w-8 transition-colors ${
                isDragging ? "text-cyan-300" : "text-zinc-500"
              }`}
            />
            <span className="mt-2 text-center text-xs text-zinc-400">
              {isDragging
                ? "松开鼠标上传"
                : "拖拽 ZIP/文件到此处，或点击选择"}
            </span>
            <span className="mt-1 text-[9px] text-zinc-600">
              ZIP 自动解压到 /vfs/workspace/{taskId}/
            </span>
          </>
        )}
      </div>

      {/* ── 上传结果 ── */}
      {uploadResult && (
        <div className="flex items-start gap-2 rounded-lg border border-emerald-500/30 bg-emerald-900/20 px-3 py-2">
          <CheckCircle2 className="h-4 w-4 flex-shrink-0 text-emerald-400" />
          <span className="text-[11px] text-emerald-300">{uploadResult}</span>
          <button
            onClick={() => setUploadResult(null)}
            className="ml-auto text-emerald-500/40 hover:text-emerald-300"
          >
            <X className="h-3 w-3" />
          </button>
        </div>
      )}

      {uploadError && (
        <div className="flex items-start gap-2 rounded-lg border border-red-500/30 bg-red-900/20 px-3 py-2">
          <AlertCircle className="h-4 w-4 flex-shrink-0 text-red-400" />
          <span className="text-[11px] text-red-300">{uploadError}</span>
          <button
            onClick={() => setUploadError(null)}
            className="ml-auto text-red-500/40 hover:text-red-300"
          >
            <X className="h-3 w-3" />
          </button>
        </div>
      )}

      {/* ── VFS 文件浏览器 ── */}
      <div>
        <div className="mb-2 flex items-center gap-2">
          <FolderTree className="h-3.5 w-3.5 text-cyan-400" />
          <span className="text-[10px] font-medium tracking-wider text-cyan-400/80 uppercase">
            VFS Browser
          </span>
        </div>

        <div className="mb-2 flex items-center gap-1">
          <input
            type="text"
            value={browsePath}
            onChange={(e) => setBrowsePath(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && browseVfs(browsePath)}
            placeholder="/vfs/workspace/..."
            className="flex-1 rounded border border-zinc-700/50 bg-zinc-900/50 px-2 py-1 font-mono text-[10px] text-zinc-300 outline-none focus:border-cyan-500/50"
          />
          <button
            onClick={() => browseVfs(browsePath)}
            disabled={browsing}
            className="rounded border border-cyan-700/40 bg-cyan-900/20 px-2 py-1 text-[9px] font-bold uppercase text-cyan-400 transition-all hover:bg-cyan-900/40 disabled:opacity-40"
          >
            {browsing ? <Loader2 className="h-3 w-3 animate-spin" /> : "Go"}
          </button>
        </div>

        {/* 文件列表 */}
        <div className="max-h-40 overflow-y-auto rounded-lg border border-zinc-800/50 bg-black/30">
          {files.length === 0 ? (
            <div className="px-3 py-4 text-center text-[10px] text-zinc-600">
              暂无文件，上传后自动刷新
            </div>
          ) : (
            files.map((file) => (
              <div
                key={file.path}
                className="group flex items-center gap-2 border-b border-zinc-800/30 px-3 py-1.5 transition-colors hover:bg-zinc-800/30"
              >
                <FileText className="h-3 w-3 flex-shrink-0 text-zinc-500" />
                <span
                  className="flex-1 truncate font-mono text-[10px] text-zinc-300 cursor-pointer hover:text-cyan-400"
                  onClick={() => previewFile(file.path)}
                  title={file.path}
                >
                  {file.name}
                </span>
                <button
                  onClick={() => previewFile(file.path)}
                  className="opacity-0 transition-opacity group-hover:opacity-100"
                  title="预览"
                >
                  <FileText className="h-3 w-3 text-cyan-500/60 hover:text-cyan-400" />
                </button>
                <button
                  onClick={() => downloadFile(file.path)}
                  className="opacity-0 transition-opacity group-hover:opacity-100"
                  title="下载"
                >
                  <Download className="h-3 w-3 text-emerald-500/60 hover:text-emerald-400" />
                </button>
              </div>
            ))
          )}
        </div>
      </div>

      {/* ── 文件预览弹窗 ── */}
      {previewPath && (
        <div className="fixed inset-0 z-[9999] flex items-center justify-center bg-black/70 backdrop-blur-sm">
          <div className="relative flex h-[70vh] w-full max-w-3xl flex-col rounded-xl border border-cyan-500/40 bg-[#0a0a0f] shadow-2xl">
            {/* 标题栏 */}
            <div className="flex items-center gap-2 border-b border-cyan-500/20 px-4 py-3">
              <FileText className="h-4 w-4 text-cyan-400" />
              <span className="flex-1 truncate font-mono text-xs text-cyan-300">
                {previewPath}
              </span>
              <button
                onClick={() => {
                  setPreviewPath(null);
                  setPreviewContent(null);
                }}
                className="rounded p-1 text-zinc-500 hover:bg-zinc-800 hover:text-zinc-300"
              >
                <X className="h-4 w-4" />
              </button>
            </div>

            {/* 内容区 */}
            <div className="flex-1 overflow-auto p-4">
              {loadingPreview ? (
                <div className="flex h-full items-center justify-center">
                  <Loader2 className="h-6 w-6 animate-spin text-cyan-400" />
                </div>
              ) : (
                <pre className="font-mono text-xs leading-relaxed text-zinc-300 whitespace-pre-wrap break-all">
                  {previewContent}
                </pre>
              )}
            </div>

            {/* 底部操作栏 */}
            <div className="flex items-center justify-end gap-2 border-t border-zinc-800 px-4 py-3">
              <button
                onClick={() => downloadFile(previewPath)}
                className="flex items-center gap-1.5 rounded-lg border border-emerald-500/40 bg-emerald-600/20 px-3 py-1.5 text-xs font-bold text-emerald-300 transition-all hover:bg-emerald-600/30"
              >
                <Download className="h-3.5 w-3.5" />
                Download
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
