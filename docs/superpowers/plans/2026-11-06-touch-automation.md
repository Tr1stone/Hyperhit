# 触摸选点和独立自动化实现计划

目标：实现用户已确认的独立连点和无限循环滑动，使用手指选点与悬浮控制。

架构：安卓服务负责手势及悬浮窗口；纯 Kotlin 运行器串行调度，界面与配置通过状态流同步。全部改动先在原工程快照中准备和验证，再核对原文件哈希后应用，不修改用户的 Git 暂存状态或自动提交。

技术栈：现有 Kotlin、Compose、Android AccessibilityService、WindowManager、JUnit4；不添加网络权限或第三方依赖。

## 任务 1：纯逻辑与测试

- [x] 创建 `app/src/test/java/com/example/hyperhit/automation/LoopControllerTest.kt`，覆盖有限多点点击、无限滑动、暂停继续、失效回调及坐标验证。
- [x] 先运行测试证明功能缺失，再实现 `AutomationModels.kt` 与 `LoopController.kt`，运行同一套测试。
- [x] 注入接口：`TaskScheduler.post(delayMs, task)` / `clear()` / `nowMs()`；`GestureSink.dispatch(action, completion): Boolean`。回调只在同一次会话内有效，未结束的手势不允许并发启动另一组动作。

## 任务 2：本地状态与服务

- [x] 创建 `AutomationStore.kt`，分别保存点击点/轮数/间隔与 A/B/滑动时长/间隔；进程重启后仍可恢复配置。
- [x] 改 `AutoClickService.kt`：按串行运行器提交 GestureDescription，验证当前屏幕几何，屏幕关闭时结束任务。
- [x] 删除旧 Activity.onStop 自动停点，改为用户悬浮控制；服务断开清理窗口与回调。

## 任务 3：悬浮与触摸选点

- [x] 创建 `overlay/AutomationOverlay.kt`，使用系统公开的 TYPE_ACCESSIBILITY_OVERLAY。
- [x] 控制条可拖动，选点窗口拦截本次手指触摸，用 rawX/rawY 保存实际屏幕像素；单指选点，双指取消，按钮可取消。
- [x] 启动位置被控制条遮挡时给出提示，重新选点或拖动控制条。
- [x] 坐标随手指实时更新；滑动起终点重合不保存。

## 任务 4：设置与验证

- [x] 改 `MainActivity.kt`：连点/滑动入口独立，滑动页面没有重复次数字段。数字输入严格校验；键盘不遮住操作区。
- [x] 保留点击测试计数，增加滚动测试区。
- [x] 运行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`，记录实际结果；通过后独立代码审查并修复问题。
- [x] 请求新项目目录写入权限，只应用列出的源码、测试与文档；先核对基线哈希，不覆盖用户在工作期间的其他修改。
- [x] 提供真机验证步骤，明确没有替用户确认真机交互。

## 验证结果（2026-11-06）

- 最终源码运行 `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`：BUILD SUCCESSFUL。
- JUnit：13 项通过，0 失败、0 错误；其中 12 项验证运行器，1 项为原有示例。
- Android Lint：0 错误、13 条警告，涉及依赖版本、模板资源和可选代码改进。
- 调试 APK 通过 apksigner v2 签名验证。
- 代码审查发现的屏幕旋转后坐标失效、RTL 拖动方向和按下暂停时的手势竞争均已修正。
- 新版真机交互尚未由开发代理执行，请按 `docs/真机测试.md` 验证。
