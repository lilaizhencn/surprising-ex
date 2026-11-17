# 前端视觉与工程审计清单（Phase 0 Baseline）

依据 [`AGENTS-frontend.md`](../AGENTS-frontend.md) 对 `surprising-ex-web/` 与 `surprising-client/` 现存实现进行代码级审计，按严重度排序如下：

## P0（阻塞统一质感与信息精度）

1. **双端设计系统分裂（SSOT 缺失）**
   - `surprising-ex-web/src/styles/tokens.css`：5 套主题名（`dark/ocean/forest/dusk/sand`）被硬编码映射到同一套 `#0a0a12` + `#00c6ff` / `#7b4397`，缺少 `oklch()` 色彩空间、5 级深空背景、3 条标准极光渐变、3 级玻璃参数与 4 级发光配额。
   - `surprising-client/lib/src/ui/client_colors.dart` 与 `client_theme.dart` 内部存在两套独立配色（`0xFF7C9AFF` 蓝紫 vs `0xFFFFC247` 琥珀金），与 Web 端完全不是同一视觉语言。
2. **交易数字可读性与防抖动基础设施不统一**
   - Web 仅局部使用 `.mono`，未对全局数字和动态表单强制 `tabular-nums`，未提供整数/小数视觉层级令牌。
   - Web 缺少 `@media (forced-colors: active)` 高对比度回退，也缺少系统化的 `@media (prefers-reduced-motion: no-preference)` 动效准入控制。

## P1（组件库残缺与默认控件残留）

3. **Web 样式单文件膨胀 & 默认控件处理粗暴**
   - `surprising-ex-web/src/styles/global.css` 单文件达 4,060 行，全局使用 `scrollbar-width: none` 隐藏滚动条而非定制深空细滚动条，字体声明（`Hanken Grotesk` vs `Inter`）与 `tokens.css` 不一致。
4. **基础组件状态矩阵缺失**
   - Web `src/components/ui/` 仅 4 个文件，缺少独立可复用的杠杆 `Slider`（刻度/磁吸/风险分段）、`SegmentedControl`、`Toast`（进度条）、`Skeleton`（数据流微光）、`Tooltip`/`Popover` 与完整 9 态交互规范（特别是极光 `focus-visible` 焦点环）。
   - Client 依赖 Material 3 `ColorScheme.fromSeed` 默认派生，缺少 `ThemeExtension` 承载发光、玻璃与动效令牌，也未建立 Golden Test 像素锁。

## P2（高级质感与性能边界）

5. **高频区隔离与降级策略未令牌化**
   - 订单簿/成交流/Ticker 需要显式禁用 `backdrop-filter` 与大面积 `box-shadow` 动画；Flutter 端 `ClientGlassSurface` 需补齐低端机/省电模式降级路径及背景 `FragmentShader` 支持。
6. **空状态、错误态与图标体系**
   - 缺乏统一的 `lucide-react` 1.5px 线宽与 Flutter 同源 SVG 规范，以及风格统一的科幻程序化空/错误/404 插画。
