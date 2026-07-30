/** @type {import('tailwindcss').Config} */

// cc-haha「Technical Atelier / Digital Archivist」设计系统令牌。
// 颜色全部走 CSS 变量（R G B 通道），在 index.css 的 :root / [data-theme='dark'] 中翻转，
// 这样组件用 bg-surface / text-on-surface 等语义类即可自动适配明暗双主题，无需逐处写 dark:。
const semanticColors = {
  background: "rgb(var(--background) / <alpha-value>)",
  surface: "rgb(var(--surface) / <alpha-value>)",
  "surface-bright": "rgb(var(--surface-bright) / <alpha-value>)",
  "surface-dim": "rgb(var(--surface-dim) / <alpha-value>)",
  "surface-container-lowest": "rgb(var(--surface-container-lowest) / <alpha-value>)",
  "surface-container-low": "rgb(var(--surface-container-low) / <alpha-value>)",
  "surface-container": "rgb(var(--surface-container) / <alpha-value>)",
  "surface-container-high": "rgb(var(--surface-container-high) / <alpha-value>)",
  "surface-container-highest": "rgb(var(--surface-container-highest) / <alpha-value>)",
  "on-surface": "rgb(var(--on-surface) / <alpha-value>)",
  "on-background": "rgb(var(--on-background) / <alpha-value>)",
  "on-surface-variant": "rgb(var(--on-surface-variant) / <alpha-value>)",
  outline: "rgb(var(--outline) / <alpha-value>)",
  "outline-variant": "rgb(var(--outline-variant) / <alpha-value>)",
  primary: "rgb(var(--primary) / <alpha-value>)",
  "on-primary": "rgb(var(--on-primary) / <alpha-value>)",
  "primary-container": "rgb(var(--primary-container) / <alpha-value>)",
  "primary-fixed": "rgb(var(--primary-fixed) / <alpha-value>)",
  tertiary: "rgb(var(--tertiary) / <alpha-value>)",
  "tertiary-container": "rgb(var(--tertiary-container) / <alpha-value>)",
  "on-tertiary-container": "rgb(var(--on-tertiary-container) / <alpha-value>)",
  secondary: "rgb(var(--secondary) / <alpha-value>)",
  error: "rgb(var(--error) / <alpha-value>)",
  "error-container": "rgb(var(--error-container) / <alpha-value>)",
  "on-error-container": "rgb(var(--on-error-container) / <alpha-value>)",
};

export default {
  darkMode: ["class", "[data-theme='dark']"],
  content: ["./index.html", "./src/**/*.{js,ts,jsx,tsx}"],
  theme: {
    container: {
      center: true,
    },
    extend: {
      colors: semanticColors,
      fontFamily: {
        headline: ["Manrope", "Inter", "system-ui", "sans-serif"],
        body: ["Inter", "system-ui", "sans-serif"],
        mono: ["JetBrains Mono", "ui-monospace", "SFMono-Regular", "Menlo", "monospace"],
      },
      borderRadius: {
        DEFAULT: "0.5rem", // 8px
        lg: "0.75rem",    // 12px
        xl: "1rem",       // 16px
        "2xl": "1.25rem",
      },
      boxShadow: {
        // 仅浮动/模态用 ——「No-Line Rule」下，深度由 surface 色阶承担。
        ambient: "0 4px 20px rgba(27, 28, 26, 0.04), 0 12px 40px rgba(27, 28, 26, 0.08)",
        "ambient-sm": "0 2px 10px rgba(27, 28, 26, 0.04), 0 6px 20px rgba(27, 28, 26, 0.06)",
      },
      animation: {
        // 自愈震动动画 — 节点报错时剧烈抖动
        shake: "shake 0.4s ease-in-out infinite",
        // 气泡淡入动画
        "fade-in": "fadeIn 0.2s ease-out",
        // 心跳呼吸
        "soft-pulse": "softPulse 2s ease-in-out infinite",
      },
      keyframes: {
        shake: {
          "0%, 100%": { transform: "translateX(-50%) translateY(-50%)" },
          "25%": { transform: "translateX(calc(-50% + 3px)) translateY(-50%)" },
          "50%": { transform: "translateX(calc(-50% - 3px)) translateY(-50%)" },
          "75%": { transform: "translateX(calc(-50% + 2px)) translateY(-50%)" },
        },
        fadeIn: {
          "0%": { opacity: "0", transform: "translateX(-50%) translateY(4px)" },
          "100%": { opacity: "1", transform: "translateX(-50%) translateY(0)" },
        },
        softPulse: {
          "0%, 100%": { opacity: "1" },
          "50%": { opacity: "0.45" },
        },
      },
    },
  },
  plugins: [],
};
