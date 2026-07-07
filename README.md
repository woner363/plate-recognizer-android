# 车牌识别 Android 应用

把 [`plate-recognizer`](../plate-recognizer/) Python 桌面版移植为 Android 原生 App。

## 功能

- 🎥 **CameraX 实时预览** + 一键抓拍
- 🤖 **ML Kit Text Recognition v2（中文）** 本地 OCR，识别过程在设备完成、无需 API key

> **隐私与网络说明**：车牌识别（OCR）在本地完成，照片与识别文本不会上传到本应用的服务器。
> 但 ML Kit 依赖会合并 `INTERNET` / `ACCESS_NETWORK_STATE` 权限（来自 Google DataTransport，
> 用于 ML Kit 自身的配置/诊断遥测），因此本应用并非"完全无网络能力"。如需严格离线，
> 可在 Manifest merger 中移除这些权限并自行验证 ML Kit 在断网下的初始化。
- 🔍 自动从识别文本中提取符合中国车牌格式（普通 / 新能源）的子串
- 📋 **Room（SQLite）** 持久化：车牌号 / 候选质量分 / 时间 / 图片 URI / 已修正 / 备注
- ✏️ **手动修正**：识别错误一键改，带格式校验和备注
- 📁 **从相册导入**：也可对已有图片做识别
- 💾 **CSV 导出**到「Download」目录（Excel 兼容 UTF-8 BOM）

## 技术栈

| 层 | 技术 |
|----|------|
| UI | Jetpack Compose + Material 3 |
| 状态 | ViewModel + StateFlow |
| 相机 | CameraX 1.3.x |
| OCR | ML Kit `text-recognition-chinese` 16.x |
| 数据 | Room 2.6.x (SQLite) |
| 权限 | Accompanist Permissions |
| 语言 | Kotlin 1.9 + JVM 17 |
| 构建 | Gradle 8.7 + AGP 8.5 |

## 目录结构

```
app/src/main/java/com/example/platerecognizer/
├── MainActivity.kt                      # 入口 + 权限门
├── AppContainer.kt                      # 手动 DI 服务定位器
├── PlateRecognizerApp.kt                # Application，启动孤儿清理
├── data/
│   ├── PlateRecord.kt                   # @Entity 正式记录（含 sourceSessionId）
│   ├── PlateDao.kt                      # PlateDao + RecognitionSessionDao（expected-state 原子迁移）
│   ├── AppDatabase.kt                   # Room Database v3 + Migration 1→2→3
│   ├── PlateRepository.kt               # PlateRecords 实现，事务性 confirmSession
│   ├── RecognitionSessionEntity.kt      # @Entity 识别 session 状态机
│   ├── RecognitionSessionRepository.kt  # RecognitionSessions 实现
│   ├── ImageStore.kt                    # ManagedImageStore 实现，.tmp+fsync+rename 导入
│   └── CsvExporter.kt                   # CsvExporter 实现，MediaStore IS_PENDING
├── domain/
│   └── Contracts.kt                     # 业务层最小接口（PlateRecords/RecognitionEngine/...）
├── ocr/
│   ├── Recognition.kt                   # 识别结果值对象（qualityScore，非模型概率）
│   └── PlateRecognizer.kt               # ML Kit 包装 + 车牌候选筛选
├── camera/
│   └── PhotoCapturer.kt                 # CameraX 拍照助手（suspend，ROI 裁剪）
├── ui/
│   ├── MainScreen.kt                    # 主屏（预览 + 按钮 + 列表）
│   ├── CameraPreviewCard.kt             # 相机预览 + 取景框（ViewPort + 释放）
│   ├── DashboardComponents.kt           # 状态药丸 + 操作按钮组件
│   ├── RecordsList.kt                   # 卡片列表 + 修正/删除
│   ├── PlateInputDialog.kt              # 输入对话框（实时校验 + rememberSaveable）
│   └── PlatesViewModel.kt               # MVVM 状态机（RecognitionUiState 单一权威源）
├── util/
│   └── PlateValidator.kt                # 中国车牌格式校验（GA 36-2018）
└── ui/theme/
    └── Theme.kt                         # Material 3 + Material You 动态取色
```

## 构建运行

```bash
# 用 Android Studio Hedgehog+ 直接打开 plate-recognizer-android/ 目录
# 或者命令行：
cd plate-recognizer-android
./gradlew :app:assembleDebug          # 输出 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug           # 安装到已连接的设备
./gradlew :app:test                   # 运行 JVM 单元测试（44 个）
```

> 首次构建会从 Maven Central / Google 仓库下载依赖，需要网络。
> 仓库已包含 Gradle Wrapper（`gradlew` / `gradlew.bat` / `gradle/wrapper/gradle-wrapper.jar`，
> 对应 Gradle 8.7），全新克隆后无需全局安装 Gradle 即可直接 `./gradlew tasks`，只需本机有 JDK 17。

## 与桌面 Python 版的对应关系

| Python 模块 | Kotlin 模块 |
|------|------|
| `src/plate_validator.py` | `util/PlateValidator.kt` |
| `src/db.py` (PlateRepository + PlateRecord) | `data/` 全套（Room） |
| `src/recognizer.py` (hyperlpr3/easyocr) | `ocr/PlateRecognizer.kt` (ML Kit 中文) |
| `src/camera.py` (OpenCV) | `camera/PhotoCapturer.kt` (CameraX) |
| `src/gui.py` (Tkinter) | `ui/MainScreen.kt` (Compose) |

## 设计要点

- ✅ **不可变更新**：`PlateRecord` 是 data class，更新走 `copy()` / `withCorrection()`，不就地修改
- ✅ **单一状态源**：`PlatesViewModel.uiState` 由 Room session Flow + transient 覆盖 combine 而成，不双源覆盖
- ✅ **expected-state 原子迁移**：session 状态机用 `WHERE state=expected` SQL，DB 层 CAS 防并发
- ✅ **幂等进程恢复**：`sourceSessionId` 唯一索引 + 事务性 confirmSession + 启动按状态补偿
- ✅ **Repository Pattern + 接口隔离**：domain 层最小接口，ViewModel 依赖抽象，便于 fake 测试
- ✅ **业务边界强制校验**：Repository.add/confirmSession/applyCorrection 都调 PlateValidator.isValid
- ✅ **显式错误处理**：所有 OCR / IO / DB 调用均 try-catch 且重抛 CancellationException，UI 用 Toast 反馈
- ✅ **测试**：44 个 JVM 测试覆盖校验器 / OCR 候选 / CSV 编码 / 孤儿清理 / ViewModel 状态机
