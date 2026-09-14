# TikCtrl

![Platform](https://img.shields.io/badge/platform-Android-green.svg)
![Language](https://img.shields.io/badge/language-Kotlin-orange.svg)
![API](https://img.shields.io/badge/API-24%2B-brightgreen.svg)
![CameraX](https://img.shields.io/badge/CameraX-1.5.0-blue.svg)
![MediaPipe](https://img.shields.io/badge/MediaPipe-Hand%20Landmarker-ff69b4.svg)

TikCtrl 是一款基于 MediaPipe Hand Landmarker 的 Android 手势控制应用。它通过摄像头实时检测手部 21 个关键点，识别手势并自动执行对应操作（如抖音的点赞、滑动切换、倍速等），让刷短视频无需触屏。

应用支持前置摄像头实时帧检测，也支持对图片和视频进行静态检测。模型文件在构建时由 Gradle 脚本自动下载，无需手动准备；如需使用自定义模型，将其放置到 `app/src/main/assets` 目录即可。

> 建议在物理 Android 设备上运行，以充分发挥摄像头功能。

## 功能特性

- **实时手势识别**：基于 MediaPipe Hand Landmarker 检测手部关键点，本地分类手势
- **手势动作映射**：内置手势映射助手，支持自定义手势与动作的对应关系
- **悬浮窗控制**：在其他应用上方显示迷你预览悬浮窗，支持拖动、最小化、切换摄像头
- **无障碍执行**：通过无障碍服务将手势转换为屏幕点击/滑动，自动操作目标应用
- **手势统计**：记录每日手势触发次数与历史数据
- **单手模式**：支持左手 / 右手 / 双手检测过滤
- **性能优化**：省电模式（10 FPS + 强制 CPU 推理）、CPU/GPU 推理引擎切换、相机帧率限制（15-20 FPS）
- **Material 3 界面**：跟随系统的深色/浅色主题，中英双语

## 支持的手势与默认动作

| 手势 | 说明 | 默认动作 |
| --- | --- | --- |
| 🖕 中指 | 仅中指伸直 | 点赞 |
| 🤙 小拇指 | 仅小拇指伸直 | 取消点赞 |
| ☝️ 食指 | 仅食指伸直 | 下一个视频 |
| ✌️ V 手势 | 食指 + 中指 | 上一个视频 |
| 三指 | 食指 + 中指 + 无名指 | 打开评论 |
| 四指 | 四指伸直 | 收藏 |
| 🤟 摇滚手势 | 拇指 + 食指 + 小拇指 | 收藏/关注 |
| 👌 OK 手势 | 拇指与食指捏合 | 查看主页 |
| 👍 点赞手势 | 仅拇指伸直 | 返回 |
| 🦊 Aki 手势 | 拇指捏住中指和无名指 | 2 倍速 |
| 🤙 Call 手势 | 拇指 + 小拇指伸直 | 1 倍速 |

> 所有映射均可在应用内通过「手势映射助手」修改。

## 技术栈

- **语言 / UI**：Kotlin、Material 3、ViewBinding
- **手势检测**：MediaPipe Tasks Vision（Hand Landmarker）
- **相机**：CameraX 1.5.0（含 Camera2Interop 帧率控制）
- **架构**：Navigation Component 单 Activity 多 Fragment、前台服务 + 无障碍服务（AccessibilityService）

## 环境要求

- [Android Studio](https://developer.android.com/studio/index.html) Dolphin 或更高版本
- 物理 Android 设备，系统版本 Android 7.0（API 24）及以上，已开启开发者模式

## 快速开始

1. 克隆本仓库：

   ```bash
   git clone https://github.com/dasuki233/TikCtrl.git
   ```

2. 打开 Android Studio，选择 **Open an existing Android Studio project**，定位到 TikCtrl 项目目录并打开（首次打开提示信任项目时选择 **Trust**）。

3. 等待 Gradle Sync 完成（首次构建会自动下载 Hand Landmarker 模型到 assets 目录）。

4. 连接已开启开发者模式的 Android 设备，点击 Run 运行。

模型文件的下载、解压与放置由 [download_tasks.gradle](app/download_tasks.gradle) 自动管理，无需手动操作。

## 使用说明

首次使用需要授予三项权限：

| 权限 | 用途 | 授予方式 |
| --- | --- | --- |
| 相机 | 检测手部手势 | 应用内首次启动时请求 |
| 悬浮窗 | 在其他应用上方显示预览 | 系统设置「显示在其他应用上层」 |
| 无障碍服务 | 执行点击/滑动等系统操作 | 系统设置 → 无障碍 → TikCtrl |

基本使用流程：

1. 打开应用，授予上述权限；
2. 在首页打开服务开关，悬浮窗出现后切换到目标应用（如抖音）；
3. 对前置摄像头做出手势，应用识别后自动执行对应动作；
4. 悬浮窗支持拖动移动、点击还原，可通过底部按钮最小化为图标。

设置项：省电模式、推理引擎（CPU/GPU）、前置/后置摄像头、镜像模式、单手模式（左/右/双手）、手势灵敏度参数与映射配置，均支持导出/导入。

## 项目结构

```
app/src/main/java/com/tikctrl/app/
├── MainActivity.kt            # 主界面与权限引导
├── HandGestureService.kt      # 前台服务：相机流 + 悬浮窗 + 手势广播
├── GestureActionService.kt    # 无障碍服务：执行点击/滑动动作
├── HandLandmarkerHelper.kt    # MediaPipe Hand Landmarker 封装
├── GestureClassifier.kt       # 基于关键点的手势分类
├── GestureMappingManager.kt   # 手势 → 动作映射管理
├── GestureStatistics.kt       # 手势触发统计
├── ConfigManager.kt           # 配置导出/导入
└── fragment/                  # 首页 / 相机 / 图库 / 权限页
```

## 常见问题

**Q: 手势识别没有反应？**
确认无障碍服务已开启且未被系统回收；检查单手模式设置是否与所用手一致；深色/浅色背景下保持手部光照充足。

**Q: GPU 推理异常或发热严重？**
GPU 初始化失败时会自动回退 CPU。也可在设置中手动切换推理引擎，或开启省电模式（10 FPS + CPU）。

**Q: 悬浮窗不见了？**
悬浮窗可最小化为小图标，点击图标即可还原；如被系统杀死，可在首页重新打开服务开关。

## 致谢

本项目基于 [MediaPipe Hand Landmarker（Android 示例）](https://github.com/googlesamples/mediapipe/tree/main/examples/hand_landmarker/android) 构建，感谢 Google MediaPipe 团队与开源社区的贡献。
