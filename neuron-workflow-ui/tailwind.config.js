/** @type {import('tailwindcss').Config} */

export default {
  darkMode: "class",
  content: ["./index.html", "./src/**/*.{js,ts,jsx,tsx}"],
  theme: {
    container: {
      center: true,
    },
    extend: {
      animation: {
        // 自愈震动动画 — 节点报错时剧烈抖动
        shake: "shake 0.4s ease-in-out infinite",
        // 气泡淡入动画
        "fade-in": "fadeIn 0.2s ease-out",
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
      },
    },
  },
  plugins: [],
};
