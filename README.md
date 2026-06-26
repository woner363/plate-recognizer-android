# 车牌识别 Android 应用

把 [`plate-recognizer`](../plate-recognizer/) Python 桌面版移植为 Android 原生 App。

## 功能

- 🎥 **CameraX 实时预览** + 一键抓拍
- 🤖 **ML Kit Text Recognition v2（中文）** 离线 OCR，无需联网、无 API key
- 🔍 自动从识别文本中提取符合中国车牌格式（普通 / 新能源）的子串
- 📋 **Room（SQLite）** 持久化：车牌号 / 置信度 / 时间 / 图片 URI / 已修正 / 备注
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
├── MainActivity.kt              # 入口 + 权限门
├── data/
│   ├── PlateRecord.kt           # @Entity 数据类（不可变更新 copy/withCorrection）
│   ├── PlateDao.kt              # Room DAO，Flow 观察
│   ├── AppDatabase.kt           # Room Database 单例
│   └── PlateRepository.kt       # Repository Pattern + CSV 导出 (MediaStore)
├── ocr/
│   ├── Recognition.kt           # 识别结果值对象
│   └── PlateRecognizer.kt       # ML Kit 包装 + 车牌候选筛选
├── camera/
│   └── PhotoCapturer.kt         # CameraX 拍照助手（suspend）
├── ui/
│   ├── MainScreen.kt            # 主屏（预览 + 按钮 + 列表）
│   ├── RecordsList.kt           # 卡片列表 + 修正/删除按钮
│   ├── PlateInputDialog.kt      # 输入对话框（实时校验）
│   └── PlatesViewModel.kt       # MVVM 状态机
└── util/
    └── PlateValidator.kt        # 中国车牌格式校验（与 Python 版等价）
```

## 构建运行

```bash
# 用 Android Studio Hedgehog+ 直接打开 plate-recognizer-android/ 目录
# 或者命令行：
cd plate-recognizer-android
./gradlew :app:assembleDebug          # 输出 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug           # 安装到已连接的设备
./gradlew :app:test                   # 运行单元测试
```

> 首次构建会从 Maven Central / Google 仓库下载依赖，需要网络。
> 由于 Gradle wrapper jar 是二进制（无法在此文本环境创建），第一次请在 Android Studio 中点击 "Sync Project" 让它自动生成，或者运行 `gradle wrapper --gradle-version 8.7`。

## 与桌面 Python 版的对应关系

| Python 模块 | Kotlin 模块 |
|------|------|
| `src/plate_validator.py` | `util/PlateValidator.kt` |
| `src/db.py` (PlateRepository + PlateRecord) | `data/` 全套（Room） |
| `src/recognizer.py` (hyperlpr3/easyocr) | `ocr/PlateRecognizer.kt` (ML Kit 中文) |
| `src/camera.py` (OpenCV) | `camera/PhotoCapturer.kt` (CameraX) |
| `src/gui.py` (Tkinter) | `ui/MainScreen.kt` (Compose) |

## 设计要点（与全局 coding-style 一致）

- ✅ **不可变更新**：`PlateRecord` 是 data class，更新走 `copy()` / `withCorrection()`，不就地修改
- ✅ **小文件高内聚**：每个 Kotlin 文件 < 250 行，按职责分包
- ✅ **Repository Pattern**：业务只依赖 `PlateRepository`，便于替换/测试
- ✅ **显式错误处理**：所有 OCR / IO / DB 调用均 try-catch，UI 用 Toast 反馈
- ✅ **输入校验**：所有手动输入车牌均经过 `PlateValidator.describeError()`
- ✅ **协程**：CameraX / ML Kit 回调封装为 `suspendCancellableCoroutine`
- ✅ **测试**：纯 JVM 测试覆盖校验器；Room 与 OCR 部分留作 Instrumentation Test
