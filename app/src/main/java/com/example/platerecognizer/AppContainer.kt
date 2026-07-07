package com.example.platerecognizer

import android.content.Context
import com.example.platerecognizer.data.AppDatabase
import com.example.platerecognizer.data.CsvExporter
import com.example.platerecognizer.data.ImageStore
import com.example.platerecognizer.data.PlateRepository
import com.example.platerecognizer.data.RecognitionSessionRepository
import com.example.platerecognizer.ocr.PlateRecognizer

/**
 * 极简服务定位器：单文件、零依赖。
 * 由 [PlateRecognizerApp] 在 onCreate 时构造一次，组件通过它取依赖；
 * 单元/Instrumentation 测试可以直接 new 一个并替换字段（开放为 var 即可）。
 *
 * 选择不用 Hilt：本工程依赖少、ViewModel 仅 1 个，编译时注解处理器代价不划算。
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.get(appContext) }
    val imageStore: ImageStore by lazy { ImageStore(appContext) }
    val repository: PlateRepository by lazy {
        PlateRepository(database, database.plateDao(), database.recognitionSessionDao(), imageStore)
    }
    val recognizer: PlateRecognizer by lazy { PlateRecognizer(appContext) }
    val sessionRepository: RecognitionSessionRepository by lazy {
        RecognitionSessionRepository(database.recognitionSessionDao())
    }
    val csvExporter: CsvExporter by lazy { CsvExporter(database.plateDao(), appContext) }
}
