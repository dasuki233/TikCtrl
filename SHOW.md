# TikCtrl 手势控制应用 - 完整代码解析文档

## 一、项目概述

TikCtrl 是一个基于 MediaPipe 的 Android 手势控制应用，通过摄像头实时检测手部手势，将其映射为系统操作（如点赞、滑动、分享等），主要用于控制短视频应用。

**核心架构**：
```
MainActivity → 权限检查 → 启动 HandGestureService
HandGestureService → 摄像头检测 → 手势识别 → 发送广播
GestureActionService → 接收广播 → 查询映射 → 执行系统操作
```

---

## 二、核心业务文件详解

### 2.1 MainActivity.kt

**文件作用**：应用主入口，负责权限管理、服务启动、UI 配置

**代码分段解析**：

| 行号 | 代码段 | 作用 |
|------|--------|------|
| 39-43 | 成员变量定义 | `activityMainBinding` 数据绑定，`viewModel` 视图模型，`startedGestureService` 服务启动标记，`REQ_CAMERA_PERM` 权限请求码 |
| 45-62 | `onCreate()` 权限检查 | 检查悬浮窗权限（Android M+）和相机权限，权限通过则启动 `HandGestureService`，否则引导用户授权 |
| 64-78 | 无障碍服务检测 | 检查 `GestureActionService` 是否已启用，未启用则弹出提示引导用户前往设置 |
| 80-92 | 布局初始化与导航 | 初始化数据绑定，配置底部导航栏，忽略重复选择事件 |
| 94-102 | FAB 点击事件 | 点击浮动按钮打开 `GestureMappingActivity` 手势映射配置界面 |
| 104-112 | 权限检测按钮 | 点击后触发 `checkAndRequestCameraAndOverlayPermission()` 检查并请求所有必要权限 |
| 114-151 | 视觉反馈设置 | 读取 `SharedPreferences` 中的视觉反馈开关状态和颜色配置，设置开关和颜色按钮的交互逻辑 |
| 154-161 | `isColorDark()` | 判断颜色亮度，用于根据背景色自动选择文字颜色 |
| 163-185 | `checkAndRequestCameraPermission()` | 检查相机权限，拒绝时显示解释对话框或引导至设置 |
| 187-221 | `checkAndRequestCameraAndOverlayPermission()` | 依次检查悬浮窗权限、无障碍服务、相机权限，任一缺失则弹出对应提示 |
| 223-233 | `onResume()` | 应用恢复时再次尝试启动手势服务（用户可能在后台授予了权限） |
| 235-268 | `onRequestPermissionsResult()` | 处理权限请求结果，授予则启动服务，拒绝则显示对应提示 |
| 270-275 | `startHandGestureServiceIfNeeded()` | 启动 `HandGestureService` 前台服务，防止重复启动 |
| 277-279 | `onBackPressed()` | 重写返回键，直接关闭应用 |

---

### 2.2 HandGestureService.kt

**文件作用**：前台服务，负责摄像头预览、手部关键点检测、手势分类、悬浮窗管理

**代码分段解析**：

| 行号 | 代码段 | 作用 |
|------|--------|------|
| 43-48 | Companion Object | `TAG` 日志标签，`CHANNEL_ID` 通知渠道，`ACTION_GESTURE` 广播动作名，`EXTRA_GESTURE` 广播参数键 |
| 50-61 | 成员变量 | `handLandmarker` MediaPipe 检测器，`cameraExecutor` 相机线程池，`gestureClassifier` 手势分类器，`windowManager` 窗口管理器，`floatingView`/`previewView` 悬浮窗视图 |
| 64-69 | 防抖控制 | `lastDetectedGesture` 上次检测手势，`consecutiveDetections` 连续检测计数，`requiredConsecutiveDetections=3` 需要连续3帧确认，`gestureCooldownMs=1500` 冷却时间1.5秒 |
| 71-83 | `onCreate()` | 启动前台服务，初始化检测器，创建悬浮窗，启动摄像头 |
| 86-105 | `startForegroundService()` | Android 8.0+ 创建通知渠道，构建并显示前台服务通知 |
| 108-130 | `setupHandLandmarker()` | 配置 MediaPipe 参数（CPU 委托、模型路径、检测数量、实时流模式），设置结果回调 |
| 133-148 | `startCamera()` | 获取 `ProcessCameraProvider`，回调中绑定相机用例 |
| 151-191 | `bindCameraUseCases()` | 创建 `ImageAnalysis` 分析器，设置帧处理逻辑；绑定 Preview 和 Analysis 到生命周期 |
| 193-201 | `switchCamera()` | 切换前后摄像头，重新绑定相机用例 |
| 204-414 | `createFloatingPreview()` | 创建悬浮窗容器，添加预览视图、关闭按钮、切换相机按钮、单手模式切换按钮、最小化按钮；实现拖拽功能；实现最小化/恢复逻辑 |
| 416-488 | `handleHandLandmarkerResult()` | 核心处理逻辑：根据单手模式过滤手部 → 调用分类器 → 稳定性校验（连续3帧）→ 冷却校验 → 发送手势广播 |
| 490-535 | `onDestroy()` | 释放所有资源：关闭检测器、解绑相机、关闭线程池、移除悬浮窗、清理引用 |
| 537-570 | 扩展方法 | `toMpImage()` 将 `ImageProxy` 转为 `MPImage`（含旋转处理），`toBitmap()` 将 `ImageProxy` 转为 `Bitmap` |

---

### 2.3 GestureActionService.kt

**文件作用**：无障碍服务，接收手势广播，执行系统操作（滑动、点击、长按等）

**代码分段解析**：

| 行号 | 代码段 | 作用 |
|------|--------|------|
| 39-43 | 协程与 Handler | `serviceScope` 后台协程作用域，`mainHandler` 主线程 Handler |
| 45-62 | `gestureReceiver` | 广播接收器，收到手势广播后在协程中调用 `performGestureAction()` |
| 64-73 | `onServiceConnected()` | 服务连接时注册广播接收器（Android 12+ 使用 `RECEIVER_NOT_EXPORTED`） |
| 77-94 | UI 变化监听 | `expectingContentChange` 和 `contentChangeLatch` 用于等待手势执行后的 UI 变化 |
| 96-123 | `swipeUp()` | 上滑手势：从屏幕中部向上滑动到顶部1/5位置，用于切换下一个视频 |
| 125-147 | `swipeDown()` | 下滑手势：从屏幕顶部1/5位置向下滑动到中部，用于切换上一个视频 |
| 149-172 | `swipeLeft()` | 左滑手势：从屏幕3/4宽度处向左滑动到1/6宽度处 |
| 174-197 | `swipeRight()` | 右滑手势：从屏幕1/6宽度处向右滑动到3/4宽度处 |
| 200-246 | `performSixTaps()` | 连续8次点击屏幕中心（50ms间隔），用于点赞操作 |
| 250-307 | `shareToTargets()` | 分享功能：点击分享按钮，等待面板加载，根据配置的目标列表选择联系人，最后点击发送 |
| 311-343 | `dumpAccessibilityTree()` | 调试方法：递归遍历无障碍树，打印所有节点信息 |
| 345-452 | `findAndClickByContentDescPartial()` | 根据 `contentDescription` 模糊搜索节点，优先点击可点击节点，支持祖先点击和坐标点击降级 |
| 454-517 | `tryClickSendInSubtree()` | 在子树中递归查找发送按钮，支持文本/描述/ID/类名多种匹配方式 |
| 519-607 | `findAndClickByTextPartial()` | 根据文本模糊搜索节点，逻辑同 `findAndClickByContentDescPartial()` |
| 609-695 | `findAndClickByViewIdPartial()` | 根据 `viewId` 模糊搜索节点，逻辑同上 |
| 697-778 | `dispatchGestureAndWaitForUiChange()` | 发送手势并等待 UI 变化，支持同步和挂起两种版本 |
| 781-789 | `longPressAtPositionAndWait()` | 在指定位置执行长按并等待 UI 变化 |
| 805-838 | `debugCurrentContentDescriptions()` | 调试方法：打印当前界面所有可访问元素的 `contentDescription` |
| 841-899 | `longPressThenFindAndClick()` | 组合操作：先长按，等待界面加载，多次重试查找并点击目标元素，用于倍速设置 |
| 902-1016 | `clickUserAvatar()` | 点击用户头像：先精确匹配 viewId，再模糊匹配，最后坐标点击 |
| 1019-1090 | `findAndClickSendNearSelectedNodes()` | 分享功能辅助：找到选中的分享目标后，在其祖先节点的子树中查找发送按钮 |
| 1095-1210 | `performGestureAction()` | 核心分发方法：将手势映射到具体动作（点赞、滑动、评论、收藏、关注、分享、返回、倍速等） |

---

### 2.4 GestureClassifier.kt

**文件作用**：手势分类器，将 MediaPipe 检测到的手部关键点转换为具体手势类型

**代码分段解析**：

| 行号 | 代码段 | 作用 |
|------|--------|------|
| 15-29 | `Gesture` 枚举 | 定义12种手势：`NONE`、`MIDDLE_FINGER`（竖中指）、`PINKY_FINGER`（竖小拇指）、`INDEX_FINGER`（竖食指）、`PEACE_V`（比耶）、`INDEX_MIDDLE_RING`（手势三）、`INDEX_MIDDLE_RING_PINKY`（手势四）、`SPIDER_MAN_SHOOTER`（🤟）、`SPIDER_SHOOTER_NO_THUMB`（🤘）、`OK`（OK手势）、`THUMB`（点赞手势）、`Aki_FOX_DEVIL`（特殊手势）、`SIXSIXSIX`（666） |
| 31-33 | 稳定性控制 | `lastGesture` 上次手势，`consecutiveCount` 连续计数，`requiredStableFrames=3` 需要3帧稳定 |
| 36-45 | `classify()` / `classifySingleHand()` | 公开方法：传入检测结果或关键点列表，委托给 `classifyFromLandmarks()` |
| 49-66 | `calculateAngle()` | 计算三点形成的角度（点B为顶点），使用向量点积和叉积计算 |
| 69-88 | `isFingerStraight()` | 判断手指是否伸直：检查 MCP-PIP-DIP 和 PIP-DIP-TIP 两个关节角度是否都大于160度 |
| 91-100 | `fingersTouch()` | 判断两指指尖是否接触：计算两点距离是否小于阈值（默认0.06） |
| 102-108 | `fingerStraights` | 计算所有手指的伸直状态，存储为 Map |
| 119-126 | 中指检测 | 中指伸直，食指/无名指/小拇指弯曲 → `MIDDLE_FINGER` |
| 128-156 | 666检测 | 小拇指伸直，其他弯曲，且拇指不接触其他手指，拇指关节角度符合条件 → `SIXSIXSIX` |
| 158-168 | 小拇指检测 | 小拇指伸直，其他弯曲，且拇指接触食指 → `PINKY_FINGER` |
| 170-179 | 食指检测 | 食指伸直，其他弯曲 → `INDEX_FINGER` |
| 181-190 | 比耶检测 | 食指和中指伸直，其他弯曲，且拇指不接触食指 → `PEACE_V` |
| 192-200 | 手势三检测 | 食指/中指/无名指伸直，拇指接触小拇指 → `INDEX_MIDDLE_RING` |
| 202-217 | 手势四检测 | 食指/中指/无名指/小拇指伸直，无名指接触小拇指，拇指不接触小拇指 → `INDEX_MIDDLE_RING_PINKY` |
| 219-240 | 🤟检测 | 食指和小拇指伸直，中指/无名指弯曲，拇指不接触中指/无名指 → `SPIDER_MAN_SHOOTER` |
| 242-285 | 🤘检测 | 食指和小拇指伸直，中指/无名指弯曲，拇指接触中指和无名指，且中指/无名指中间关节弯曲 → `SPIDER_SHOOTER_NO_THUMB` |
| 287-293 | OK检测 | 拇指接触食指，中指/无名指/小拇指伸直 → `OK` |
| 295-347 | 点赞检测 | 拇指伸直，其他手指弯曲，且各手指中间关节角度符合条件 → `THUMB` |
| 349-388 | 特殊手势检测 | 拇指接触中指和无名指，食指和小拇指伸直，且中指/无名指中间关节伸直 → `Aki_FOX_DEVIL` |

---

### 2.5 GestureMappingManager.kt

**文件作用**：手势-动作映射管理器，使用 SharedPreferences 持久化配置

**代码分段解析**：

| 行号 | 代码段 | 作用 |
|------|--------|------|
| 14-30 | `Action` 枚举 | 定义13种动作：`NONE`、`NEXT`、`PREV`、`SHARE`、`LIKE`、`UNLIKE`、`DOUBLE_SPEED`、`NORMAL_SPEED`、`FOLLOW`、`MARK`、`BACKK`、`OPEN_COMMENTS`、`CUSTOM`、`USER_AVATAR` |
| 32-33 | `prefs()` | 获取 SharedPreferences 实例 |
| 35-39 | `getActionForGesture()` | 根据手势获取对应的动作，无映射时使用默认映射 |
| 41-43 | `setActionForGesture()` | 设置手势到动作的映射，写入 SharedPreferences |
| 45-47 | `resetMappings()` | 清除所有映射，恢复默认配置 |
| 52-61 | `setActionParam()` / `getActionParam()` | 设置/获取动作参数（如分享目标列表） |
| 63-69 | `setCustomLabel()` / `getCustomLabel()` | 设置/获取自定义标签（未使用） |
| 71-92 | `getDisplayName()` | 获取动作的显示名称，优先使用自定义标签，否则使用 strings.xml 中的本地化字符串 |
| 95-100 | `getAllActionsForDisplay()` | 获取所有可显示的动作列表（用于下拉框） |
| 103-118 | `defaultMapping()` | 默认映射配置：竖中指→点赞、小拇指→取消点赞、食指→下一个、比耶→上一个、手势三→评论、手势四→收藏、🤟→关注、OK→主页、点赞手势→返回、特殊手势→二倍速、666→一倍速 |
| 121-142 | `getGestureDisplayName()` | 获取手势的显示名称，从 strings.xml 读取本地化字符串 |
| 145-164 | 单手模式管理 | `SingleHandMode` 枚举（BOTH/LEFT/RIGHT），`setSingleHandMode()`/`getSingleHandMode()` 持久化单手模式配置 |

---

### 2.6 GestureMappingActivity.kt

**文件作用**：手势映射配置界面，用户可自定义每个手势对应的动作

**代码分段解析**：

| 行号 | 代码段 | 作用 |
|------|--------|------|
| 13 | `container` | 手势映射列表的容器 LinearLayout |
| 15-20 | `onCreate()` 初始化 | 设置布局，获取容器和重置按钮 |
| 22-46 | 单手模式绑定 | 获取 RadioGroup，根据当前模式设置选中状态，监听变化并保存 |
| 48-50 | 构建动作列表 | 获取所有动作及其显示名称，用于 Spinner |
| 52-147 | 手势循环 | 遍历所有手势（跳过 NONE），为每个手势创建一行配置项：TextView（手势名称）+ Spinner（动作选择）+ EditText（参数输入），设置 Spinner 的选中状态和变化监听，添加参数输入框的焦点和文本变化监听 |
| 145-149 | 重置按钮 | 点击后清除所有映射，重新创建 Activity 刷新界面 |
| 152 | `dp()` | 将 dp 转换为 px |

---

## 三、备用 UI 文件（未被主流程使用）

### 3.1 GestureActionManager.kt

**文件作用**：早期开发阶段的手势动作管理器，**当前未被使用**

**代码解析**：
- 定义 `performAction()` 方法，遍历所有手势并打印日志
- 功能已被 `GestureActionService` 完全替代

### 3.2 GestureConfig.kt

**文件作用**：早期开发阶段的手势-动作映射表，**当前未被使用**

**代码解析**：
- 定义 `gestureActionMap` 静态映射表
- 功能已被 `GestureMappingManager.defaultMapping()` 替代

### 3.3 MainViewModel.kt

**文件作用**：相机 Fragment 的视图模型，存储检测参数配置

**代码解析**：
- 存储 `delegate`（CPU/GPU）、`minHandDetectionConfidence`（检测置信度）、`minHandTrackingConfidence`（追踪置信度）、`minHandPresenceConfidence`（存在置信度）、`maxHands`（最大检测数量）
- 提供 getter 和 setter 方法
- 被 `CameraFragment` 和 `GalleryFragment` 使用

### 3.4 HandLandmarkerHelper.kt

**文件作用**：MediaPipe 手部关键点检测封装，支持实时流、图片、视频三种模式

**代码解析**：
- 构造函数接收检测参数、运行模式、上下文和回调监听器
- `setupHandLandmarker()` 初始化检测器
- `detectLiveStream()` 处理实时摄像头流
- `detectVideoFile()` 处理视频文件
- `detectImage()` 处理单张图片
- `imageProxyToBitmap()` 将 ImageProxy 转为 Bitmap（支持 YUV_420_888 和 RGBA）
- `yuv420ToNv21()` YUV 到 NV21 格式转换
- 被 `CameraFragment` 和 `GalleryFragment` 使用

### 3.5 OverlayView.kt

**文件作用**：手部关键点叠加层视图，在相机预览或图片上绘制骨骼

**代码解析**：
- `initPaints()` 初始化画笔（红线绘制点，彩色线绘制骨骼）
- `draw()` 遍历检测结果，绘制所有关键点和连接
- `setResults()` 设置检测结果和图片尺寸，计算缩放因子
- 被 `CameraFragment` 和 `GalleryFragment` 使用

### 3.6 CameraFragment.kt

**文件作用**：相机预览 Fragment，提供实时手部检测和参数调整界面

**代码解析**：
- `onResume()`/`onPause()`/`onDestroyView()` 生命周期管理，保存和恢复检测参数
- `onViewCreated()` 初始化相机和检测器
- `initBottomSheetControls()` 绑定底部控制面板（检测阈值、追踪阈值、存在阈值、最大手数、推理设备）
- `bindCameraUseCases()` 绑定 CameraX 预览和分析用例
- `onResults()` 接收检测结果，更新 UI，调用手势分类并发送广播
- `detectHand()` 将 ImageProxy 传入检测器

---

## 四、数据流向

```
摄像头图像 → CameraX ImageAnalysis → ImageProxy → MPImage
                                                    ↓
                                           HandLandmarker.detectAsync()
                                                    ↓
                                           HandLandmarkerResult（21个关键点）
                                                    ↓
                                           GestureClassifier.classify()
                                                    ↓
                                           Gesture 枚举值（如 MIDDLE_FINGER）
                                                    ↓
                                           稳定性校验（连续3帧一致）
                                                    ↓
                                           冷却校验（1.5秒间隔）
                                                    ↓
                                           发送广播（ACTION_GESTURE + EXTRA_GESTURE）
                                                    ↓
                                           GestureActionService 接收广播
                                                    ↓
                                           GestureMappingManager.getActionForGesture()
                                                    ↓
                                           执行具体动作（swipeUp/click/longPress 等）
```

---

## 五、支持的手势与默认动作

| 手势 | 默认动作 | 触发条件 |
|------|---------|---------|
| `MIDDLE_FINGER` | 点赞 | 中指伸直，其他弯曲 |
| `PINKY_FINGER` | 取消点赞 | 小拇指伸直，拇指接触食指 |
| `INDEX_FINGER` | 下一个视频 | 食指伸直，其他弯曲 |
| `PEACE_V` | 上一个视频 | 食指+中指伸直，其他弯曲 |
| `INDEX_MIDDLE_RING` | 打开评论 | 食指+中指+无名指伸直，拇指接触小拇指 |
| `INDEX_MIDDLE_RING_PINKY` | 收藏 | 四指伸直，无名指接触小拇指 |
| `SPIDER_MAN_SHOOTER` | 关注 | 食指+小拇指伸直，拇指不接触中指/无名指 |
| `SPIDER_SHOOTER_NO_THUMB` | 无 | 食指+小拇指伸直，拇指接触中指+无名指 |
| `OK` | 查看主页 | 拇指接触食指，其他三指伸直 |
| `THUMB` | 返回 | 拇指伸直，其他手指弯曲 |
| `Aki_FOX_DEVIL` | 二倍速 | 拇指接触中指+无名指，食指+小拇指伸直 |
| `SIXSIXSIX` | 一倍速 | 小拇指伸直，其他弯曲，拇指伸直 |

---

## 六、支持的动作

| 动作 | 功能 | 实现方式 |
|------|------|---------|
| `NONE` | 无操作 | 不执行任何动作 |
| `NEXT` | 上滑（下一个视频） | `swipeUp()` |
| `PREV` | 下滑（上一个视频） | `swipeDown()` |
| `LIKE` | 点赞 | `performSixTaps()` 连续8次点击 |
| `UNLIKE` | 取消点赞 | `findAndClickByContentDescPartial("已点赞")` |
| `OPEN_COMMENTS` | 打开评论 | `findAndClickByContentDescPartial("评论")` |
| `MARK` | 收藏 | `findAndClickByContentDescPartial("收藏")` |
| `FOLLOW` | 关注 | `findAndClickByContentDescPartial("关注")` |
| `USER_AVATAR` | 查看主页 | `clickUserAvatar()` 根据 viewId 查找 |
| `SHARE` | 分享 | `shareToTargets()` 打开分享面板并选择目标 |
| `BACKK` | 返回 | `performGlobalAction(GLOBAL_ACTION_BACK)` |
| `DOUBLE_SPEED` | 二倍速 | `longPressThenFindAndClick("2.0")` |
| `NORMAL_SPEED` | 一倍速 | `longPressThenFindAndClick("1.0")` |
