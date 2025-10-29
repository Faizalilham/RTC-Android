package dev.faizal.rtcandroid.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.faizal.rtcandroid.repository.MainRepository
import dev.faizal.rtcandroid.service.MainServiceActions.*
import dev.faizal.rtcandroid.utils.DataModel
import dev.faizal.rtcandroid.utils.DataModelType
import dev.faizal.rtcandroid.utils.isValid
import dagger.hilt.android.AndroidEntryPoint
import dev.faizal.rtcandroid.R
import dev.faizal.rtcandroid.webrtc.RTCAudioManager
import org.webrtc.SurfaceViewRenderer
import javax.inject.Inject

@AndroidEntryPoint
class MainService : Service(), MainRepository.Listener {

    private val TAG = "MainService"

    private var isServiceRunning = false
    private var username: String? = null
    private var isScreenSharing = false

    @Inject
    lateinit var mainRepository: MainRepository

    private lateinit var notificationManager: NotificationManager
    private lateinit var rtcAudioManager: RTCAudioManager
    private var isPreviousCallStateVideo = true

    companion object {
        var listener: Listener? = null
        var endCallListener: EndCallListener? = null
        var localSurfaceView: SurfaceViewRenderer? = null
        var remoteSurfaceView: SurfaceViewRenderer? = null
        var screenPermissionIntent: Intent? = null
    }

    override fun onCreate() {
        super.onCreate()
        rtcAudioManager = RTCAudioManager.create(this)
        rtcAudioManager.setDefaultAudioDevice(RTCAudioManager.AudioDevice.SPEAKER_PHONE)
        notificationManager = getSystemService(NotificationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let { incomingIntent ->
            when (incomingIntent.action) {
                START_SERVICE.name -> handleStartService(incomingIntent)
                SETUP_VIEWS.name -> handleSetupViews(incomingIntent)
                END_CALL.name -> handleEndCall()
                SWITCH_CAMERA.name -> handleSwitchCamera()
                TOGGLE_AUDIO.name -> handleToggleAudio(incomingIntent)
                TOGGLE_VIDEO.name -> handleToggleVideo(incomingIntent)
                TOGGLE_AUDIO_DEVICE.name -> handleToggleAudioDevice(incomingIntent)
                TOGGLE_SCREEN_SHARE.name -> handleToggleScreenShare(incomingIntent)
                STOP_SERVICE.name -> handleStopService()
                else -> Unit
            }
        }

        return START_STICKY
    }

    private fun handleToggleAudio(incomingIntent: Intent) {
        // ⭐⭐⭐ CRITICAL FIX: Default = FALSE (unmuted)
        val shouldBeMuted = incomingIntent.getBooleanExtra("shouldBeMuted", false)

        Log.d(TAG, "========== HANDLE TOGGLE AUDIO ==========")
        Log.d(TAG, "Received shouldBeMuted: $shouldBeMuted")

        mainRepository.toggleAudio(shouldBeMuted)

        // Verify dengan delay
        Handler(Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "Audio toggle completed")
        }, 100)

        Log.d(TAG, "=========================================")
    }

    private fun handleSetupViews(incomingIntent: Intent) {
        val isCaller = incomingIntent.getBooleanExtra("isCaller", false)
        val isVideoCall = incomingIntent.getBooleanExtra("isVideoCall", true)
        val target = incomingIntent.getStringExtra("target")

        // Add null checks for surface views
        if (localSurfaceView == null || remoteSurfaceView == null) {
            Log.e(TAG, "handleSetupViews: Surface views are null. localSurfaceView=$localSurfaceView, remoteSurfaceView=$remoteSurfaceView")
            return
        }

        if (target == null) {
            Log.e(TAG, "handleSetupViews: target is null")
            return
        }

        Log.d(TAG, "========== SETUP VIEWS ==========")
        Log.d(TAG, "Remote view: $remoteSurfaceView")
        Log.d(TAG, "Local view: $localSurfaceView")
        Log.d(TAG, "Target: $target")
        Log.d(TAG, "Is video call: $isVideoCall")
        Log.d(TAG, "Is caller: $isCaller")

        this.isPreviousCallStateVideo = isVideoCall
        mainRepository.setTarget(target)

        // Initialize local view (with camera if video call)
        mainRepository.initLocalSurfaceView(localSurfaceView!!, isVideoCall)

        // Setup remote view
        mainRepository.setupRemoteViewFirst()
        mainRepository.initRemoteSurfaceView(remoteSurfaceView!!)

        // Start call if answering (not caller)
        if (!isCaller) {
            Handler(Looper.getMainLooper()).postDelayed({
                Log.d(TAG, "Starting call (as answerer)")
                mainRepository.startCall()
            }, 300)
        }

        // ⭐⭐⭐ CRITICAL: Force unmute audio setelah setup
        Handler(Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "🔊 FORCE UNMUTING audio after setup")
            mainRepository.toggleAudio(false) // false = unmuted
        }, 1000)

        Log.d(TAG, "=================================")
    }

    private fun handleToggleVideo(incomingIntent: Intent) {
        val shouldBeMuted = incomingIntent.getBooleanExtra("shouldBeMuted", false)
        this.isPreviousCallStateVideo = !shouldBeMuted
        mainRepository.toggleVideo(shouldBeMuted)
    }

    private fun handleToggleAudioDevice(incomingIntent: Intent) {
        val type = when (incomingIntent.getStringExtra("type")) {
            RTCAudioManager.AudioDevice.EARPIECE.name -> RTCAudioManager.AudioDevice.EARPIECE
            RTCAudioManager.AudioDevice.SPEAKER_PHONE.name -> RTCAudioManager.AudioDevice.SPEAKER_PHONE
            else -> null
        }

        type?.let {
            rtcAudioManager.setDefaultAudioDevice(it)
            rtcAudioManager.selectAudioDevice(it)
            Log.d(TAG, "handleToggleAudioDevice: $it")
        }
    }

    private fun handleSwitchCamera() {
        mainRepository.switchCamera()
    }

    private fun handleEndCall() {
        // Send signal to other peer that call is ended
        mainRepository.sendEndCall()
        // End call and restart repository
        endCallAndRestartRepository()
    }

    private fun endCallAndRestartRepository() {
        mainRepository.endCall()
        endCallListener?.onCallEnded()
        mainRepository.initWebrtcClient(username!!)
    }

    private fun handleToggleScreenShare(incomingIntent: Intent) {
        val isStarting = incomingIntent.getBooleanExtra("isStarting", true)
        if (isStarting) {
            // Stop camera streaming first if it was on
            if (isPreviousCallStateVideo) {
                mainRepository.toggleVideo(true)
            }

            if (screenPermissionIntent == null) {
                Log.e(TAG, "handleToggleScreenShare: screenPermissionIntent is null")
                return
            }

            mainRepository.setScreenCaptureIntent(screenPermissionIntent!!)
            mainRepository.toggleScreenShare(true)

            // Update foreground service type to include mediaProjection
            isScreenSharing = true
            updateForegroundServiceType()

        } else {
            // Stop screen share and restore camera if it was on
            mainRepository.toggleScreenShare(false)
            if (isPreviousCallStateVideo) {
                mainRepository.toggleVideo(false)
            }

            // Update foreground service type to remove mediaProjection
            isScreenSharing = false
            updateForegroundServiceType()
        }
    }

    private fun updateForegroundServiceType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val notificationChannel = NotificationChannel(
                "channel1", "foreground", NotificationManager.IMPORTANCE_HIGH
            )

            val intent = Intent(this, MainServiceReceiver::class.java).apply {
                action = "ACTION_EXIT"
            }
            val pendingIntent: PendingIntent =
                PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

            notificationManager.createNotificationChannel(notificationChannel)
            val notification = NotificationCompat.Builder(
                this, "channel1"
            ).setSmallIcon(R.mipmap.ic_launcher)
                .addAction(R.drawable.ic_end_call, "Exit", pendingIntent)
                .build()

            // Determine which service types are needed
            val serviceType = if (isScreenSharing) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(1, notification, serviceType)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, serviceType)
            } else {
                startForeground(1, notification)
            }
        }
    }

    private fun handleStartService(incomingIntent: Intent) {
        if (!isServiceRunning) {
            isServiceRunning = true
            username = incomingIntent.getStringExtra("username")
            startServiceWithNotification()

            // Setup clients
            mainRepository.listener = this
            mainRepository.initFirebase()
            mainRepository.initWebrtcClient(username!!)

            Log.d(TAG, "Service started for user: $username")
        }
    }

    private fun startServiceWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationChannel = NotificationChannel(
                "channel1", "foreground", NotificationManager.IMPORTANCE_HIGH
            )

            val intent = Intent(this, MainServiceReceiver::class.java).apply {
                action = "ACTION_EXIT"
            }
            val pendingIntent: PendingIntent =
                PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

            notificationManager.createNotificationChannel(notificationChannel)
            val notification = NotificationCompat.Builder(
                this, "channel1"
            ).setSmallIcon(R.mipmap.ic_launcher)
                .addAction(R.drawable.ic_end_call, "Exit", pendingIntent)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                    1,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, 0)
            } else {
                startForeground(1, notification)
            }
        }
    }

    private fun handleStopService() {
        mainRepository.endCall()
        mainRepository.logOff {
            isServiceRunning = false
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onLatestEventReceived(data: DataModel) {
        if (data.isValid()) {
            when (data.type) {
                DataModelType.StartVideoCall,
                DataModelType.StartAudioCall -> {
                    listener?.onCallReceived(data)
                }
                else -> Unit
            }
        }
    }

    override fun endCall() {
        // Receiving end call signal from remote peer
        endCallAndRestartRepository()
    }

    interface Listener {
        fun onCallReceived(model: DataModel)
    }

    interface EndCallListener {
        fun onCallEnded()
    }
}