package trucker.geminilive.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import trucker.geminilive.MainActivity

/**
 * BroadcastReceiver to handle Samsung Galaxy Tab Active 5 Active Key (XCover Key) events.
 * Listens for Samsung Knox HARD_KEY_REPORT intent and launches the app when the Active Key is pressed.
 */
class ActiveKeyReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ActiveKeyReceiver"
        
        // Samsung Knox intent action for hardware key events
        const val ACTION_HARD_KEY_REPORT = "com.samsung.android.knox.intent.action.HARD_KEY_REPORT"
        
        // Extra key for key code
        const val EXTRA_KEY_CODE = "com.samsung.android.knox.intent.extra.KEY_CODE"
        
        // Extra key for report type (press/release)
        const val EXTRA_KEY_REPORT_TYPE = "com.samsung.android.knox.intent.extra.KEY_REPORT_TYPE_NEW"
        
        // Possible Active Key codes: 26 (Side/Power), 1015 (XCover Side), 1016 (XCover Top)
        val ACTIVE_KEY_CODES = listOf(26, 1015, 1016)
        
        // Report type for key press (1 is typically "pressed", 2 is "released")
        const val REPORT_TYPE_PRESS = 1
        const val REPORT_TYPE_PRESS_ALT = 0
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")
        
        // Log all extras to help debug what the device is sending
        intent.extras?.keySet()?.forEach { key ->
            Log.d(TAG, "Extra: $key = ${intent.extras?.get(key)}")
        }

        if (intent.action != ACTION_HARD_KEY_REPORT) {
            return
        }

        val keyCode = intent.getIntExtra(EXTRA_KEY_CODE, -1)
        val reportType = intent.getIntExtra(EXTRA_KEY_REPORT_TYPE, -1)

        Log.d(TAG, "Hardware Key Event: keyCode=$keyCode, reportType=$reportType")

        // Check if this is an Active Key press event
        if (ACTIVE_KEY_CODES.contains(keyCode) && 
            (reportType == REPORT_TYPE_PRESS || reportType == REPORT_TYPE_PRESS_ALT)) {
            
            Log.d(TAG, "Active Key match found - attempting to launch app")
            
            // Show a toast so the user knows the key press was detected
            android.widget.Toast.makeText(context, "Active Key Pressed - Starting App", android.widget.Toast.LENGTH_SHORT).show()

            launchApp(context)
        }
    }

    private fun launchApp(context: Context) {
        // First, check if we need to wake up the screen
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        val wakeLock = powerManager.newWakeLock(
            android.os.PowerManager.FULL_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE, "TruckerLive:WakeLock"
        )
        wakeLock.acquire(3000)

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            // Important flags for background start
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra("LAUNCHED_FROM_ACTIVE_KEY", true)
        }
        
        try {
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                Log.d(TAG, "App launch intent sent successfully")
            } else {
                Log.e(TAG, "Could not find launch intent for package")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app", e)
        }
    }
}
