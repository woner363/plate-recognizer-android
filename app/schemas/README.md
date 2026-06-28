# Room schemas

此目录存放 Room 通过 KSP 导出的 schema JSON，按 `<完整Database类名>/<version>.json` 命名。

## 用途

1. **DB 迁移审查**：每次 `@Database(version=N)` 提升，Room 会生成 `N.json` 与上一版的 diff；Code review 时可直接对照 SQL 变更。
2. **MigrationTestHelper**：`androidx.room:room-testing` 可在 Instrumentation Test 中用 `MigrationTestHelper(InstrumentationRegistry, AppDatabase::class.java)` 加载这里的旧版本 schema，验证 `Migration` 类对真实 SQL 落地正确。本工程已通过 `androidTest.assets.srcDir` 把此目录挂入 `androidTest` assets。

## 生成

首次 `./gradlew :app:assembleDebug` 或 Android Studio Sync + Build 之后，Room 会自动写入：

```
app/schemas/com.example.platerecognizer.data.AppDatabase/1.json
```

请把生成的 JSON 一并 commit 入 git。后续如需升级版本号：

1. `AppDatabase.version = 2`
2. 编写并注册 `Migration(1, 2)` 到 `Room.databaseBuilder(...).addMigrations(...)`；
3. 重新 build → `2.json` 落盘；
4. （可选）写 `MigrationTest` 验证。
