package com.cardoctor.demo.demo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cardoctor.demo.engine.Wheel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Demo control from the PC (tools/demo_console.py). The app has no demo UI of its own: telltales
 * and wipers are injected into the emulator's Virtual Console by the PC tool, and the few values
 * that live only inside the app (simulated tire pressures, environment overrides, sample voice
 * questions) arrive here as adb broadcasts.
 *
 * The receiver is guarded by android.permission.DUMP, which the adb shell holds and ordinary
 * apps cannot get, so nothing on the car can drive it.
 *
 *   adb shell am broadcast --user 10 -n com.cardoctor.demo/.demo.DemoCommandReceiver \
 *       -a com.cardoctor.demo.DEMO --es cmd tire --es wheel RIGHT_REAR --ez low true
 */
sealed interface DemoCommand {
    data class Tire(val wheel: Wheel, val low: Boolean) : DemoCommand
    data object ClearTires : DemoCommand
    /** key: night | weather | driving (value auto|on|off), highway | long (value on|off). */
    data class Env(val key: String, val value: String) : DemoCommand
    data object LockEnvironment : DemoCommand
    data object ResetEnvironment : DemoCommand
    /** Index into VoiceCommands.SAMPLES: adb cannot pass Korean text reliably. */
    data class Ask(val index: Int) : DemoCommand
}

object DemoBus {
    private val _commands = MutableSharedFlow<DemoCommand>(extraBufferCapacity = 32)
    val commands: SharedFlow<DemoCommand> = _commands
    fun send(command: DemoCommand) = _commands.tryEmit(command)
}

class DemoCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val command = parse(intent)
        if (command == null) {
            Log.w(TAG, "unknown demo command: ${intent.extras?.keySet()?.associateWith { intent.extras?.get(it) }}")
            return
        }
        DemoBus.send(command)
    }

    private fun parse(intent: Intent): DemoCommand? = when (intent.getStringExtra("cmd")) {
        "tire" -> runCatching { Wheel.valueOf(intent.getStringExtra("wheel") ?: "") }.getOrNull()
            ?.let { DemoCommand.Tire(it, intent.getBooleanExtra("low", true)) }
        "tires_clear" -> DemoCommand.ClearTires
        "env" -> {
            val key = intent.getStringExtra("key")
            val value = intent.getStringExtra("value")
            if (key != null && value != null) DemoCommand.Env(key, value) else null
        }
        "env_lock" -> DemoCommand.LockEnvironment
        "env_reset" -> DemoCommand.ResetEnvironment
        "ask" -> DemoCommand.Ask(intent.getIntExtra("q", -1))
        else -> null
    }

    private companion object { const val TAG = "DemoCommand" }
}

/** Monitor entries go to logcat (tag CarDoctorLog); the PC tool streams them into its LIVE LOG. */
enum class LogKind(val tag: String) {
    INJECT("주입"), SIGNAL("신호"), JUDGE("판정"), ENVIRONMENT("환경"), OVERALL("종합")
}
