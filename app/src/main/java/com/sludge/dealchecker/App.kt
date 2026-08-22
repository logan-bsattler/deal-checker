package com.sludge.dealchecker

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Catches anything that kills the process — including exceptions on the index-loading thread —
 * and writes the stack trace where the next launch can show it. Sideloaded builds have no other
 * way to surface a crash without a laptop and adb.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                File(filesDir, "last-crash.txt").writeText(
                    "thread: ${thread.name}\n" +
                        "android: ${android.os.Build.VERSION.SDK_INT} on ${android.os.Build.MODEL}\n\n" +
                        sw.toString()
                )
            } catch (_: Throwable) {
                // nothing useful left to do
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
