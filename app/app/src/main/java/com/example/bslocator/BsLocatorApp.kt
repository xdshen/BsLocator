package com.example.bslocator

import android.app.Application
import com.example.bslocator.data.MeasurementDatabase
import com.example.bslocator.util.CsvImporter

class BsLocatorApp : Application() {
    val database by lazy { MeasurementDatabase.getDatabase(this) }

    override fun onCreate() {
        super.onCreate()
        // 自动从CSV备份恢复数据（仅执行一次）
        CsvImporter.importIfNeeded(this)
    }
}
