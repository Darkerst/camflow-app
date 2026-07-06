package com.darkerst.cameraflow

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

/**
 * Catches uncaught exceptions, saves the stack trace to local storage,
 * then restarts the app so MainActivity can display it on screen.
 * This lets you read a crash's stack trace directly on the device,
 * without adb or a computer.
 */
object CrashHandler {
    private const val PREFS_NAME = "crash_prefs"
    private const val KEY_TRACE = "last_crash_trace"

    fun install(context: Context) {
        val appContext = context.applicationContext

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            try {
                val trace = Log.getStackTraceString(throwable)
                appContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_TRACE, trace)
                    .commit()

                val restartIntent = Intent(appContext, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                appContext.startActivity(restartIntent)
            } catch (_: Throwable) {
                // If saving/restarting itself fails, just fall through to the
                // normal crash/kill below rather than looping forever.
            } finally {
                Process.killProcess(Process.myPid())
                exitProcess(1)
            }
        }
    }

    fun getLastCrash(context: Context): String? =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_TRACE, null)

    fun clearLastCrash(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_TRACE)
            .apply()
    }
}
