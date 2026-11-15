package com.example.tvcleaner

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var modeView: TextView
    private lateinit var status: TextView
    private lateinit var button: Button

    @Volatile
    private var busy = false

    // Приложения, которые никогда не останавливаем
    private val excluded = setOf(
        "moe.shizuku.privileged.api" // сам Shizuku, иначе он остановится
    )

    private val binderReceived = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshState() }
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        runOnUiThread { refreshState() }
    }
    private val permissionResult =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            runOnUiThread { refreshState() }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        modeView = findViewById(R.id.mode)
        status = findViewById(R.id.status)
        button = findViewById(R.id.btnClean)

        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)

        button.setOnClickListener { onButton() }
        button.requestFocus()
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        super.onDestroy()
    }

    // ---------- Состояние Shizuku ----------

    private fun shizukuRunning(): Boolean =
        try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    private fun shizukuReady(): Boolean =
        try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }

    private fun ramLine(): String =
        "Свободно ОЗУ: ${freeRamMb()} МБ из ${totalRamMb()} МБ"

    private fun refreshState() {
        modeView.text = when {
            shizukuReady() -> "Режим: полная остановка (Shizuku)"
            shizukuRunning() -> "Shizuku запущен. Нажмите кнопку и разрешите доступ"
            else -> "Shizuku не запущен. Режим: только фоновые процессы"
        }
        if (busy) return

        if (shizukuReady()) {
            status.text = "Считаю запущенные приложения…\n${ramLine()}"
            Thread {
                val n = try { runningPackages().size } catch (_: Throwable) { -1 }
                runOnUiThread {
                    if (!busy) {
                        val head = if (n >= 0) "Запущено приложений: $n\n" else ""
                        status.text = head + ramLine()
                    }
                }
            }.start()
        } else {
            status.text = ramLine() +
                "\n(счётчик запущенных приложений работает в режиме Shizuku)"
        }
    }

    // ---------- Кнопка ----------

    private fun onButton() {
        when {
            shizukuReady() -> cleanWithShizuku()
            shizukuRunning() -> try {
                Shizuku.requestPermission(1001)
            } catch (_: Throwable) {
                status.text = "Не удалось запросить доступ у Shizuku"
            }
            else -> cleanWithKill()
        }
    }

    // ---------- Память ----------

    private fun memInfo(): ActivityManager.MemoryInfo {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    }

    private fun freeRamMb(): Long = memInfo().availMem / (1024 * 1024)
    private fun totalRamMb(): Long = memInfo().totalMem / (1024 * 1024)

    private fun launcherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .toSet()
    }

    // ---------- Shell через Shizuku ----------

    // В новых версиях Shizuku newProcess() приватный, поэтому вызываем через рефлексию
    private fun runShell(cmd: String): String {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out
    }

    /** Сторонние (не системные) приложения без исключений. Вызывать не из UI-потока. */
    private fun userPackages(): List<String> {
        val skip = launcherPackages() + excluded + packageName
        return runShell("pm list packages -3 2>&1")
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .filter { it.isNotEmpty() && it !in skip }
    }

    /** Имена всех процессов в системе. Вызывать не из UI-потока. */
    private fun processNames(): Set<String> =
        runShell("ps -A -o NAME 2>/dev/null || ps 2>/dev/null")
            .lines()
            .map { it.trim().substringAfterLast(' ') }
            .filter { it.isNotEmpty() }
            .toSet()

    /** Из списка пакетов оставляем те, у которых есть живой процесс (name или name:service). */
    private fun filterRunning(pkgs: List<String>, names: Set<String>): List<String> =
        pkgs.filter { p -> names.any { it == p || it.startsWith("$p:") } }

    /** Сколько сторонних приложений сейчас имеют процесс. Вызывать не из UI-потока. */
    private fun runningPackages(): List<String> =
        filterRunning(userPackages(), processNames())

    // ---------- Режим 1: force-stop через Shizuku ----------

    private fun cleanWithShizuku() {
        busy = true
        button.isEnabled = false
        status.text = "Останавливаю приложения…"
        val before = freeRamMb()

        Thread {
            var wasRunning = 0
            var left = 0
            var error: String? = null
            try {
                val all = userPackages()
                val names = processNames()
                var targets = filterRunning(all, names)
                // Если определить процессы не удалось, останавливаем все сторонние
                if (targets.isEmpty() && names.size < 5) targets = all
                wasRunning = targets.size

                if (targets.isNotEmpty()) {
                    runShell(targets.joinToString("; ") { "am force-stop $it" } + " 2>&1")
                }
                Thread.sleep(1200)
                left = runningPackages().size
            } catch (t: Throwable) {
                error = t.message ?: t.javaClass.simpleName
            }

            runOnUiThread {
                busy = false
                val after = freeRamMb()
                status.text = if (error != null) {
                    "Ошибка: $error"
                } else {
                    "Было запущено: $wasRunning\n" +
                        "Осталось запущено: $left\n" +
                        "Освобождено: ${(after - before).coerceAtLeast(0)} МБ\n" +
                        ramLine()
                }
                button.isEnabled = true
                button.requestFocus()
            }
        }.start()
    }

    // ---------- Режим 2 (запасной): только фоновые процессы ----------

    private fun cleanWithKill() {
        busy = true
        button.isEnabled = false
        status.text = "Очистка…"
        val before = freeRamMb()
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val skip = launcherPackages() + excluded + packageName

        var count = 0
        for (app in packageManager.getInstalledApplications(PackageManager.GET_META_DATA)) {
            if (app.packageName in skip) continue
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            try {
                am.killBackgroundProcesses(app.packageName)
                count++
            } catch (_: Exception) {
            }
        }

        status.postDelayed({
            busy = false
            val after = freeRamMb()
            status.text = "Обработано приложений: $count\n" +
                "Освобождено: ${(after - before).coerceAtLeast(0)} МБ\n" +
                ramLine()
            button.isEnabled = true
            button.requestFocus()
        }, 1200)
    }
}
