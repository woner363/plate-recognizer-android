# Plate Recognizer Android 修改建议

## 1. 审阅范围

- 仓库：`woner363/plate-recognizer-android`
- 远端基线：`main` 分支提交 `c2c6bb552e32538cbe69bae1e77fe38e3195fa9d`
- 审阅对象：远端基线加当前工作区尚未提交的修改
- 审阅重点：可重复构建、数据一致性、协程并发、图片生命周期、隐私、CSV 安全、OCR 可信度及测试覆盖

当前改动已经解决了部分初始问题，包括无效 Compose 插件声明、备注二次更新竞态、Android 9 及以下导出权限、一次性事件相互覆盖，以及删除记录时清理已关联图片。但项目仍有若干发布阻断项和运行时风险。

## 2. 优先级总览

| 优先级 | 建议 | 主要风险 |
|---|---|---|
| P0 | 提交完整 Gradle Wrapper 并建立 CI | 仓库无法按 README 构建或测试 |
| P1 | 移除列表中捕获旧记录的缓存回调 | 二次编辑可能覆盖新数据 |
| P1 | 建立待确认图片的完整生命周期 | 产生孤立图片、旋转后丢失确认任务 |
| P1 | 串行化拍摄、导入和 OCR | 并发结果互相覆盖，忙碌状态失真 |
| P1 | 升级到符合发布要求的目标 API | 无法提交 Google Play 新应用或更新 |
| P1 | 排除数据库和车牌图片的云备份 | 敏感数据进入系统云备份 |
| P1 | 完善数据库及文件操作异常处理 | 未捕获异常可能终止应用 |
| P2 | 修正或删除 ML Kit 模型元数据 | 配置与实际依赖模式不一致 |
| P2 | 修复 CSV 地区格式和公式注入 | 导出列错位或触发表格公式 |
| P2 | 重构 OCR 置信度与车牌校验规则 | 启发式分数被误当成模型可信度 |
| P2 | 解耦相机权限与非相机功能 | 拒绝相机权限后无法导入或导出 |
| P2 | 补齐自动化测试 | 修改后缺少可靠回归验证 |

## 3. 详细修改建议

### 3.1 P0：恢复可重复构建

涉及文件：

- [`README.md`](README.md)
- [`gradle/wrapper/gradle-wrapper.properties`](gradle/wrapper/gradle-wrapper.properties)

#### 当前问题

仓库只有 `gradle-wrapper.properties`，缺少以下文件：

- `gradlew`
- `gradlew.bat`
- `gradle/wrapper/gradle-wrapper.jar`

因此 README 中的 `./gradlew` 命令无法执行。Android Studio 同步也不应被当作生成 Wrapper 的可靠替代方案。

#### 建议修改

1. 在安装了 Gradle 和 JDK 17 的环境执行：

   ```bash
   gradle wrapper --gradle-version 8.7
   ```

2. 将上述三个缺失文件连同现有 `gradle-wrapper.properties` 一起提交。
3. 添加 GitHub Actions，在每次推送和拉取请求中运行：

   ```bash
   ./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
   ```

4. 修正 README 中“Android Studio 会自动生成 Wrapper”的描述。

#### 验收标准

- 全新克隆仓库后无需全局安装 Gradle即可运行 `./gradlew tasks`。
- 单元测试、Lint 和 Debug APK 构建在 CI 中全部通过。
- `git status` 不产生未提交的 Wrapper 或 Room schema 文件。

---

### 3.2 P1：移除捕获旧 `PlateRecord` 的回调缓存

涉及文件：[`RecordsList.kt`](app/src/main/java/com/example/platerecognizer/ui/RecordsList.kt)

#### 当前问题

`RecordCard` 使用 `remember(r.id, onEdit)` 缓存回调，但回调内部捕获整个 `r`。记录内容发生变化而 ID 不变时，界面可能显示新数据，点击编辑却仍拿到旧对象。

#### 建议修改

这里没有必要为了减少一次轻量级 Lambda 分配而缓存回调，直接使用当前参数：

```kotlin
IconButton(onClick = { onEdit(r) }) {
    Icon(Icons.Default.Edit, contentDescription = "修正")
}
IconButton(onClick = { onDelete(r) }) {
    Icon(Icons.Default.Delete, contentDescription = "删除")
}
```

如果确实需要稳定回调，应使用 `rememberUpdatedState(r)` 保证读取最新记录，而不是只以 ID 作为 key。

#### 验收标准

- 同一记录连续修正两次时，第二次对话框展示第一次修正后的车牌和备注。
- 编辑旧记录不会覆盖较新的字段值。

---

### 3.3 P1：建立待确认图片生命周期

涉及文件：

- [`ImageStore.kt`](app/src/main/java/com/example/platerecognizer/data/ImageStore.kt)
- [`PhotoCapturer.kt`](app/src/main/java/com/example/platerecognizer/camera/PhotoCapturer.kt)
- [`PlatesViewModel.kt`](app/src/main/java/com/example/platerecognizer/ui/PlatesViewModel.kt)
- [`MainScreen.kt`](app/src/main/java/com/example/platerecognizer/ui/MainScreen.kt)

#### 当前问题

抓拍和相册导入都会立即在 `filesDir/plates` 创建永久文件。以下情况不会删除文件：

- 用户关闭识别确认对话框；
- Activity 旋转后，使用 `remember` 保存的确认状态丢失；
- 拍照协程取消，但 CameraX 稍后仍完成写入；
- 图片复制或 OCR 中途失败；
- 应用进程在入库前被终止。

#### 建议修改

1. 在 ViewModel 中维护持久的待确认状态，例如：

   ```kotlin
   data class PendingRecognition(
       val plateNo: String,
       val confidence: Float,
       val imageUri: Uri,
       val error: String?,
   )

   private val _pending = MutableStateFlow<PendingRecognition?>(null)
   val pending: StateFlow<PendingRecognition?> = _pending.asStateFlow()
   ```

2. `MainScreen` 从 `pending` 渲染对话框，不再把确认请求作为一次性 Channel 事件。
3. 提供两个明确入口：

   - `confirmPending(plateNo, note)`：入库成功后清空 pending；
   - `discardPending()`：删除自有图片后清空 pending。

4. `ImageStore.importToLocal()` 先写临时文件，完整复制成功后再原子重命名；失败时在 `finally` 中删除临时文件。
5. 启动时扫描 `filesDir/plates`，清理超过合理期限且未被数据库引用的孤立文件。
6. CameraX 在协程取消后仍保存成功时，应在回调中删除刚生成的文件。

#### 验收标准

- 关闭确认对话框后，对应图片立即删除。
- 旋转屏幕后确认对话框及内容仍存在。
- 模拟复制失败、OCR 失败和协程取消后，不留下新文件。
- 数据库中的每个自有 `imageUri` 都指向存在的文件。

---

### 3.4 P1：串行化图片处理任务

涉及文件：[`PlatesViewModel.kt`](app/src/main/java/com/example/platerecognizer/ui/PlatesViewModel.kt)

#### 当前问题

相册导入会先启动一个协程复制图片，再调用 `onImageCaptured()` 启动第二个协程。拍照保存期间也尚未设置 `isProcessing`。快速点击可能启动多个任务，而单个 Boolean 无法正确表达多个并发任务的状态。

#### 建议修改

将“导入或拍摄完成后的 URI → OCR → 自动保存或进入待确认状态”收敛成一个 suspend 流程，并使用 `Mutex` 或当前 Job 防止并发：

```kotlin
private val recognitionMutex = Mutex()

private fun launchRecognition(block: suspend () -> Uri) {
    viewModelScope.launch {
        if (!recognitionMutex.tryLock()) {
            emit(UiEvent.Toast("已有识别任务正在进行"))
            return@launch
        }
        _isProcessing.value = true
        try {
            processImage(block())
        } finally {
            _isProcessing.value = false
            recognitionMutex.unlock()
        }
    }
}
```

拍摄按钮的忙碌状态还应覆盖 CameraX 保存过程，而不只是 OCR。

#### 验收标准

- 快速连续点击拍摄或相册按钮只会执行一个任务。
- `isProcessing` 在整个复制、拍照和 OCR 期间保持为 `true`。
- 不会出现一个确认框被另一个识别结果替换的情况。

---

### 3.5 P1：升级目标 API 和构建工具

涉及文件：

- [`app/build.gradle.kts`](app/build.gradle.kts)
- [`gradle/libs.versions.toml`](gradle/libs.versions.toml)

#### 当前问题

项目使用 `compileSdk = 34`、`targetSdk = 34`。Google Play 从 2025 年 8 月 31 日起要求新的手机应用及更新至少面向 Android 15（API 35）。

#### 建议修改

1. 将 `compileSdk` 和 `targetSdk` 至少升级到 35。
2. 同步升级到明确支持该 compile SDK 的 Android Gradle Plugin。
3. 对照 Android 15 行为变更测试：文件访问、边到边显示、相机权限和后台行为。
4. 若应用只做内部安装，也应在 README 中明确发布边界。

参考资料：[Google Play 目标 API 要求](https://developer.android.com/google/play/requirements/target-sdk)

#### 验收标准

- Play Console 预检查不再报告目标 API 过低。
- Android 15/16 模拟器上的拍照、相册、Room 和 CSV 流程通过测试。

---

### 3.6 P1：禁止车牌数据进入云备份

涉及文件：

- [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml)
- [`backup_rules.xml`](app/src/main/res/xml/backup_rules.xml)
- [`data_extraction_rules.xml`](app/src/main/res/xml/data_extraction_rules.xml)

#### 当前问题

应用启用了 `allowBackup`，两个规则文件未排除任何数据。车牌记录、备注以及复制到私有目录的照片会被纳入备份范围。

#### 建议修改

如果不需要备份，最简单的方案是设置：

```xml
android:allowBackup="false"
```

如果需要保留其他设置的备份，应至少在新旧规则中排除：

- 数据库域中的 `plates.db` 及其日志文件；
- 文件域中的 `plates/` 目录。

是否允许设备到设备迁移应作为独立产品决策，不应默认与云备份采用相同策略。

#### 验收标准

- `adb shell bmgr` 备份检查中不包含数据库和图片。
- 应用卸载并恢复备份后，不会恢复旧车牌照片或记录。

---

### 3.7 P1：统一异常和取消处理

涉及文件：

- [`PlatesViewModel.kt`](app/src/main/java/com/example/platerecognizer/ui/PlatesViewModel.kt)
- [`PlateRepository.kt`](app/src/main/java/com/example/platerecognizer/data/PlateRepository.kt)

#### 当前问题

高置信度识别的 `repo.add()` 和 `delete()` 没有捕获数据库异常。部分代码捕获 `Throwable`，会连协程的 `CancellationException` 一并吞掉。MediaStore 创建文件后写入失败，也可能留下空文件。

#### 建议修改

1. 对用户操作统一使用可复用的异常边界。
2. 不要笼统捕获 `Throwable`；捕获取消异常时必须重新抛出：

   ```kotlin
   catch (e: CancellationException) {
       throw e
   } catch (e: Exception) {
       emit(UiEvent.Toast("保存失败: ${e.message ?: "未知错误"}"))
   }
   ```

3. 自动保存失败时保留为待确认状态，避免图片和识别结果直接丢失。
4. MediaStore 写入使用 `IS_PENDING`；发生异常时删除刚创建的条目。
5. 删除记录和图片需要明确一致性策略。推荐先确认图片可删除，再在事务中删除记录，或记录清理失败并安排重试。

#### 验收标准

- 模拟磁盘已满、数据库异常和导出流打开失败时，应用不崩溃。
- 协程取消不会产生错误 Toast，也不会继续写数据库。
- MediaStore 中不留下失败导出的空 CSV。

---

### 3.8 P2：修正 ML Kit 模型配置

涉及文件：

- [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml)
- [`gradle/libs.versions.toml`](gradle/libs.versions.toml)

#### 当前问题

项目使用 `com.google.mlkit:text-recognition-chinese`，这是随 APK/AAB 打包的模型；Manifest 却声明安装后下载模型，并使用了与中文 OCR 不匹配的 `ica` 值。

#### 建议修改

- 继续使用 bundled 依赖时，删除 `com.google.mlkit.vision.DEPENDENCIES` 元数据。
- 如果改用 Google Play Services 的 unbundled 依赖，则改成对应依赖，并使用官方要求的 `ocr_chinese` 值，同时处理模型尚未下载时的状态。
- 升级到当前稳定的小版本前，执行一次真机首启及离线识别测试。

参考资料：[ML Kit Android 文本识别](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)

#### 验收标准

- 飞行模式下首次启动 bundled 版本仍能识别。
- Manifest merger 输出中不再包含无效模型代码。

---

### 3.9 P2：修复 CSV 格式与安全问题

涉及文件：[`PlateRepository.kt`](app/src/main/java/com/example/platerecognizer/data/PlateRepository.kt)

#### 当前问题

- `"%.2f".format(confidence)` 使用系统默认 Locale。在逗号作为小数点的地区会把一个字段拆成两列。
- 备注、车牌或 URI 以 `=`, `+`, `-`, `@` 开头时，Excel 等软件可能按公式解析。
- 转义逻辑没有处理回车 `\r`。

#### 建议修改

1. 数值使用稳定 Locale：

   ```kotlin
   String.format(Locale.US, "%.2f", r.confidence)
   ```

2. 在 CSV 转义前中和公式前缀，例如添加单引号。是否保留原始内容应在导出规范中说明。
3. 包含逗号、双引号、`\r` 或 `\n` 的字段统一加双引号。
4. 为 `buildCsv()` 提取独立类并增加 JVM 单测。

#### 验收标准

- 在中文、德文和法文 Locale 下生成完全相同的列结构。
- 备注 `=1+1` 在 Excel 中显示为文本，不执行公式。
- 含逗号、双引号和换行的备注可以正确重新解析。

---

### 3.10 P2：区分 OCR 置信度与业务评分

涉及文件：

- [`PlateRecognizer.kt`](app/src/main/java/com/example/platerecognizer/ocr/PlateRecognizer.kt)
- [`Recognition.kt`](app/src/main/java/com/example/platerecognizer/ocr/Recognition.kt)
- [`PlateValidator.kt`](app/src/main/java/com/example/platerecognizer/util/PlateValidator.kt)

#### 当前问题

当前 `confidence` 是根据字符串长度和字符组成映射得到的启发式分数，不是 ML Kit 输出的模型置信度，却被用于 `>= 0.9` 自动入库。校验器只检查省份、长度和字母数字组合，新能源牌规则过宽，也允许容易混淆或通常不允许的字符组合。

#### 建议修改

1. 将结果拆分为不同概念：

   ```kotlin
   data class Recognition(
       val plateNo: String,
       val ocrConfidence: Float?,
       val ruleScore: Float,
       val requiresConfirmation: Boolean,
   )
   ```

2. 从 ML Kit 的 block、line、element 或 symbol 读取可用的置信度和边界信息。
3. 不要因为正则命中就直接赋予 `0.95` 以上分数。
4. 在完成真实图片数据集评估前，默认要求人工确认。
5. 按产品支持范围明确实现普通、新能源、警用、挂车、使领馆等规则；不支持的类型应明确提示，而不是误判为通用合法车牌。
6. 避免无条件拼接所有 OCR 行，因为跨行文本可能偶然组成合法车牌。

#### 验收标准

- 业务评分不再被展示为 OCR 模型置信度。
- 自动保存阈值由带标注的数据集评估确定。
- 测试覆盖合法、非法、易混淆字符、跨行误拼接和多候选场景。

---

### 3.11 P2：相机权限不应阻断整个应用

涉及文件：

- [`MainActivity.kt`](app/src/main/java/com/example/platerecognizer/MainActivity.kt)
- [`MainScreen.kt`](app/src/main/java/com/example/platerecognizer/ui/MainScreen.kt)

#### 当前问题

用户拒绝相机权限后，整个 `MainScreen` 都不会显示，因此无法从相册导入、浏览记录或导出 CSV。此外，`cameraProviderFuture.get()` 位于 `try` 外，Provider 初始化失败仍可能在主线程抛出异常。

#### 建议修改

1. 始终展示主界面，仅在相机预览和拍照按钮上应用权限状态。
2. 相册、记录和导出不依赖相机权限。
3. 永久拒绝权限后提供跳转系统设置的说明。
4. 将 `cameraProviderFuture.get()` 和绑定过程全部放入异常处理，并通过 UI 显示不可用状态。
5. Manifest 中是否必须声明 `android.hardware.camera` 为 required，应根据是否允许纯相册模式决定。

#### 验收标准

- 拒绝相机权限后仍可导入图片、查看记录和导出 CSV。
- 无摄像头模拟器或 Provider 初始化失败时应用不崩溃。

---

### 3.12 P2：补齐测试体系

建议至少增加以下测试：

#### JVM 单元测试

- `PlateValidator`：新能源规则、I/O 混淆、全角字符、非法省份和边界长度；
- OCR 候选：跨行误拼接、多候选排序、重复候选、非法 8 位组合；
- CSV：Locale、引号、换行、公式前缀和 BOM；
- ViewModel：识别失败、自动保存失败、确认、丢弃、并发点击和取消；
- 图片生命周期：复制失败、清理失败和孤立文件扫描。

#### Instrumentation 测试

- Room 插入、修正、删除及 schema migration；
- Android 8/9 存储权限导出；
- Android 10+ MediaStore 导出；
- Photo Picker 回退路径；
- Activity 旋转后待确认状态恢复。

#### 真机或设备矩阵

- API 24、28、29、35，以及当前最新稳定 Android；
- 有/无 Google Play Services；
- 中文、英文及逗号小数 Locale；
- 拒绝、临时授予和永久拒绝相机权限。

## 4. 推荐实施顺序

### 第一阶段：让项目可信地构建

1. 补全 Gradle Wrapper。
2. 升级 compile/target SDK 与 AGP。
3. 建立 GitHub Actions。
4. 确保单元测试、Lint 和 Debug 构建通过。

### 第二阶段：修复数据与生命周期问题

1. 删除 `RecordsList` 的旧对象回调缓存。
2. 把待确认识别改为 ViewModel 状态。
3. 串行化拍照、导入和 OCR。
4. 实现确认、丢弃、失败和启动清理。
5. 完成数据库、文件及 MediaStore 的错误处理。

### 第三阶段：提高发布质量

1. 收紧备份策略。
2. 修复 CSV 安全与 Locale。
3. 修正 ML Kit 配置。
4. 重构 OCR 可信度及车牌规则。
5. 解耦相机权限与其他功能。
6. 补齐 Instrumentation 测试和真机验证。

## 5. 最终验收清单

- [ ] 全新克隆后 `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 成功。
- [ ] CI 在拉取请求中自动执行构建和测试。
- [ ] 无相机权限时仍可导入、查看和导出。
- [ ] 多次快速点击不会产生并发识别任务。
- [ ] 旋转、取消、失败和应用重启后没有孤立图片。
- [ ] 连续编辑同一记录不会读写旧对象。
- [ ] 数据库和车牌图片不进入云备份。
- [ ] CSV 在不同 Locale 和 Excel 中安全、列结构稳定。
- [ ] OCR 规则评分与模型置信度分开表达。
- [ ] Google Play 目标 API 检查通过。
- [ ] Android 24、28、29、35 及最新稳定版本完成关键流程验证。

