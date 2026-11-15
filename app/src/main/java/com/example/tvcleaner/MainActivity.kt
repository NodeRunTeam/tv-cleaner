package com.example.tvcleaner

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var button: Button
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        status = findViewById(R.id.status)
        button = findViewById(R.id.btnClean)

        showFreeRam()

        button.setOnClickListener { cleanAll() }
        button.requestFocus()
    }

    private fun freeRamMb(): Long {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    private fun totalRamMb(): Long {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem / (1024 * 1024)
    }

    private fun showFreeRam() {
        status.text = "Свободно ОЗУ: ${freeRamMb()} МБ из ${totalRamMb()} МБ"
    }

    private fun launcherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager
            .queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .toSet()
    }

    private fun cleanAll() {
        button.isEnabled = false
        status.text = "Очистка…"

        val before = freeRamMb()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val skip = launcherPackages() + packageName

        var count = 0
        val apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in apps) {
            val pkg = app.packageName
            if (pkg in skip) continue
            // Системные приложения не трогаем
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem) continue
            try {
                am.killBackgroundProcesses(pkg)
                count++
            } catch (_: Exception) {
            }
        }

        // Даём системе секунду, чтобы освободить память
        handler.postDelayed({
            val after = freeRamMb()
            val freed = (after - before).coerceAtLeast(0)
            status.text = "Обработано приложений: $count\n" +
                "Освобождено: $freed МБ\n" +
                "Свободно ОЗУ: $after МБ из ${totalRamMb()} МБ"
            button.isEnabled = true
            button.requestFocus()
        }, 1200)
    }
}
