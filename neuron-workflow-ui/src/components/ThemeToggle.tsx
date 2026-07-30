import { useEffect, useState } from "react";
import { Sun, Moon } from "lucide-react";
import { cn } from "@/lib/utils";

type Theme = "light" | "dark";

function readTheme(): Theme {
  const stored = typeof localStorage !== "undefined" ? localStorage.getItem("aios-theme") : null;
  if (stored === "dark" || stored === "light") return stored;
  if (typeof window !== "undefined" && window.matchMedia?.("(prefers-color-scheme: dark)").matches) {
    return "dark";
  }
  return "light";
}

/** 明暗主题切换 —— 写 localStorage + 同步 document.documentElement.dataset.theme */
export default function ThemeToggle({ className }: { className?: string }) {
  const [theme, setTheme] = useState<Theme>(readTheme);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try {
      localStorage.setItem("aios-theme", theme);
    } catch {
      /* localStorage 不可用时静默降级 */
    }
  }, [theme]);

  const isDark = theme === "dark";

  return (
    <button
      onClick={() => setTheme(isDark ? "light" : "dark")}
      title={isDark ? "切换到暖纸亮色" : "切换到石墨深色"}
      aria-label="切换主题"
      className={cn(
        "flex h-8 w-8 items-center justify-center rounded-lg text-outline transition-colors hover:bg-surface-container-high hover:text-primary",
        className,
      )}
    >
      {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
    </button>
  );
}
