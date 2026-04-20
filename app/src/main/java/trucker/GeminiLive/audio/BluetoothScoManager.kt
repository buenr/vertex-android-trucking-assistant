package trucker.geminilive.audio

import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.util.Log

/**
 * Manages Bluetooth SCO (Synchronous Connection-Oriented) audio routing for trucker headsets.
 * 
 * Truck drivers typically use heavy-duty Bluetooth headsets (like BlueParrott) that require
 * explicit SCO connection for two-way audio. Android doesn't automatically route media app
 * audio through these devices - we must start a SCO session to capture mic input and route
 * playback to the earpiece.
 */
class BluetoothScoManager(private val context: Context) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    @Volatile private var isScoConnected = false
    @Volatile private var isStarted = false
    
    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    when (state) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                            Log.d("BluetoothScoManager", "SCO audio connected")
                            isScoConnected = true
                        }
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                            Log.d("BluetoothScoManager", "SCO audio disconnected")
                            isScoConnected = false
                        }
                        AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                            Log.d("BluetoothScoManager", "SCO audio connecting...")
                        }
                        else -> {
                            Log.w("BluetoothScoManager", "Unknown SCO state: $state")
                        }
                    }
                }
                BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, -1)
                    Log.d("BluetoothScoManager", "Headset audio state changed: $state")
                }
            }
        }
    }
    
    /**
     * Starts Bluetooth SCO connection. Call this before starting audio recording/playback
     * to ensure audio routes through the connected Bluetooth headset.
     */
    fun start(): Boolean {
        if (isStarted) {
            Log.d("BluetoothScoManager", "Already started")
            return isScoConnected
        }
        
        // Check if Bluetooth headset is connected
        if (!audioManager.isBluetoothScoAvailableOffCall) {
            Log.w("BluetoothScoManager", "Bluetooth SCO not available")
            return false
        }
        
        Log.d("BluetoothScoManager", "Starting Bluetooth SCO...")
        
        // Register receiver for SCO state changes
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
        }
        
        try {
            context.registerReceiver(scoReceiver, filter)
        } catch (e: Exception) {
            Log.e("BluetoothScoManager", "Failed to register receiver", e)
        }
        
        // Start SCO connection
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        isStarted = true
        
        Log.d("BluetoothScoManager", "SCO start requested, waiting for connection...")
        return true
    }
    
    /**
     * Stops Bluetooth SCO connection. Call this when ending the audio session
     * to release the Bluetooth audio channel.
     */
    fun stop() {
        if (!isStarted) {
            return
        }
        
        Log.d("BluetoothScoManager", "Stopping Bluetooth SCO...")
        
        try {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        } catch (e: Exception) {
            Log.e("BluetoothScoManager", "Error stopping SCO", e)
        }
        
        try {
            context.unregisterReceiver(scoReceiver)
        } catch (e: Exception) {
            Log.e("BluetoothScoManager", "Error unregistering receiver", e)
        }
        
        isStarted = false
        isScoConnected = false
    }
    
    /**
     * Returns true if SCO audio is currently connected and active.
     */
    fun isScoActive(): Boolean = isScoConnected
    
    /**
     * Returns true if a Bluetooth headset is connected and SCO is available.
     */
    fun isHeadsetAvailable(): Boolean = audioManager.isBluetoothScoAvailableOffCall
}
