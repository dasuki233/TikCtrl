# TikCtrl

### Overview

TikCtrl 是一款基于 MediaPipe 的手势控制应用，通过摄像头实时检测手部关键点，实现手势识别和控制功能。该应用支持从设备前置摄像头的连续帧、图片或视频中检测手部地标。

应用使用自定义的 **task** 文件进行手势识别。在构建和运行应用时，task 文件会通过 Gradle 脚本自动下载，无需手动操作。如果需要使用自定义的地标检测任务文件，请将其放置到 app 的 *assets* 目录中。

建议在物理 Android 设备上运行此应用以充分利用摄像头功能。

## Build the demo using Android Studio

### Prerequisites

*   **[Android Studio](https://developer.android.com/studio/index.html)** IDE。本项目已在 Android Studio Dolphin 及更高版本上测试通过。

*   物理 Android 设备，最低 OS 版本为 SDK 24（Android 7.0 - Nougat），并已启用开发者模式。不同设备启用开发者模式的步骤可能有所不同。

### Building

*   打开 Android Studio。从欢迎界面选择 "Open an existing Android Studio project"。

*   在弹出的 "Open File or Project" 窗口中，导航到并选择 TikCtrl 项目目录。点击 OK。可能会提示是否信任该项目，请选择 Trust。

*   如果提示进行 Gradle Sync，请点击 OK。

*   将 Android 设备连接到电脑并启用开发者模式后，点击 Android Studio 中的绿色 Run 箭头。

### Models used

模型的下载、解压和放置到 *assets* 文件夹的过程由 **download_tasks.gradle** 文件自动管理。

### Features

- 实时手部关键点检测
- 手势识别与分类
- 手势动作映射配置
- 悬浮窗手势控制
- Material3 深色主题界面