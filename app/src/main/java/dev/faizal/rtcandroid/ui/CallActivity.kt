package dev.faizal.rtcandroid.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import dev.faizal.rtcandroid.service.MainService
import dev.faizal.rtcandroid.service.MainServiceRepository
import dagger.hilt.android.AndroidEntryPoint
import dev.faizal.rtcandroid.R
import dev.faizal.rtcandroid.databinding.ActivityCallBinding
import dev.faizal.rtcandroid.utils.convertToHumanTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import dev.faizal.rtcandroid.webrtc.RTCAudioManager


@AndroidEntryPoint
class CallActivity : AppCompatActivity(), MainService.EndCallListener {

    private var viewsInitialized = false

    private var target:String?=null
    private var isVideoCall:Boolean= true
    private var isCaller:Boolean = true

    private var isMicrophoneMuted = false
    private var isCameraMuted = false
    private var isSpeakerMode = true
    private var isScreenCasting = false


    @Inject lateinit var serviceRepository: MainServiceRepository
    private lateinit var requestScreenCaptureLauncher:ActivityResultLauncher<Intent>

    private lateinit var views:ActivityCallBinding

    override fun onStart() {
        super.onStart()
        requestScreenCaptureLauncher = registerForActivityResult(ActivityResultContracts
            .StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK){
                val intent = result.data
                //its time to give this intent to our service and service passes it to our webrtc client
                MainService.screenPermissionIntent = intent
                isScreenCasting = true
                updateUiToScreenCaptureIsOn()
                serviceRepository.toggleScreenShare(true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        views = ActivityCallBinding.inflate(layoutInflater)
        setContentView(views.root)
        init()
    }

    private fun init() {
        intent.getStringExtra("target")?.let {
            this.target = it
        } ?: kotlin.run {
            finish()
        }

        isVideoCall = intent.getBooleanExtra("isVideoCall", true)
        isCaller = intent.getBooleanExtra("isCaller", true)

        views.apply {
            callTitleTv.text = "In call with $target"

            CoroutineScope(Dispatchers.IO).launch {
                for (i in 0..3600) {
                    delay(1000)
                    withContext(Dispatchers.Main) {
                        callTimerTv.text = i.convertToHumanTime()
                    }
                }
            }

            if (!isVideoCall) {
                toggleCameraButton.isVisible = false
                screenShareButton.isVisible = false
                switchCameraButton.isVisible = false
            }

            // ========== CRITICAL: Init views ONLY ONCE ==========
            if (!viewsInitialized) {
                setupSurfaceViews()
            } else {
                Log.w("CallActivity", "⚠️ Views already initialized, skipping")
            }

            endCallButton.setOnClickListener {
                serviceRepository.sendEndCall()
            }

            switchCameraButton.setOnClickListener {
                serviceRepository.switchCamera()
            }
        }

        setupMicToggleClicked()
        setupCameraToggleClicked()
        setupToggleAudioDevice()
        setupScreenCasting()
        MainService.endCallListener = this
    }
    private fun setupScreenCasting() {
        views.apply {
            screenShareButton.setOnClickListener {
                if (!isScreenCasting){
                    //we have to start casting
                    AlertDialog.Builder(this@CallActivity)
                        .setTitle("Screen Casting")
                        .setMessage("You sure to start casting ?")
                        .setPositiveButton("Yes"){dialog,_ ->
                            //start screen casting process
                            startScreenCapture()
                            dialog.dismiss()
                        }.setNegativeButton("No") {dialog,_ ->
                            dialog.dismiss()
                        }.create().show()
                }else{
                    //we have to end screen casting
                    isScreenCasting = false
                    updateUiToScreenCaptureIsOff()
                    serviceRepository.toggleScreenShare(false)
                }
            }

        }
    }

    private fun setupSurfaceViews() {
        Log.d("CallActivity", "========== setupSurfaceViews START ==========")

        views.remoteView.post {
            views.remoteView.post {
                Log.d("CallActivity", "Views ready for setup")
                Log.d("CallActivity", "Remote: ${views.remoteView.width}x${views.remoteView.height}")
                Log.d("CallActivity", "Local: ${views.localView.width}x${views.localView.height}")

                // Assign to service ONCE
                if (MainService.remoteSurfaceView == null) {
                    MainService.remoteSurfaceView = views.remoteView
                    Log.d("CallActivity", "✅ Remote view assigned to service")
                }

                if (MainService.localSurfaceView == null) {
                    MainService.localSurfaceView = views.localView
                    Log.d("CallActivity", "✅ Local view assigned to service")
                }

                // Mark as initialized
                viewsInitialized = true

                // Setup WebRTC with delay
                Handler(Looper.getMainLooper()).postDelayed({
                    Log.d("CallActivity", "Calling setupViews...")
                    serviceRepository.setupViews(isVideoCall, isCaller, target!!)
                }, 100)
            }
        }

        Log.d("CallActivity", "========== setupSurfaceViews END ==========")
    }

    private fun startScreenCapture() {
        val mediaProjectionManager = application.getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        val captureIntent = mediaProjectionManager.createScreenCaptureIntent()
        requestScreenCaptureLauncher.launch(captureIntent)

    }

    private fun updateUiToScreenCaptureIsOn(){
        views.apply {
            localView.isVisible = false
            switchCameraButton.isVisible = false
            toggleCameraButton.isVisible = false
            screenShareButton.setImageResource(R.drawable.ic_stop_screen_share)
        }

    }
    private fun updateUiToScreenCaptureIsOff() {
        views.apply {
            localView.isVisible = true
            switchCameraButton.isVisible = true
            toggleCameraButton.isVisible = true
            screenShareButton.setImageResource(R.drawable.ic_screen_share)
        }
    }
    private fun setupMicToggleClicked(){
        views.apply {
            toggleMicrophoneButton.setOnClickListener {
                if (!isMicrophoneMuted){
                    //we should mute our mic
                    //1. send a command to repository
                    serviceRepository.toggleAudio(true)
                    //2. update ui to mic is muted
                    toggleMicrophoneButton.setImageResource(R.drawable.ic_mic_on)
                }else{
                    //we should set it back to normal
                    //1. send a command to repository to make it back to normal status
                    serviceRepository.toggleAudio(false)
                    //2. update ui
                    toggleMicrophoneButton.setImageResource(R.drawable.ic_mic_off)
                }
                isMicrophoneMuted = !isMicrophoneMuted
            }
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        serviceRepository.sendEndCall()
    }

    private fun setupToggleAudioDevice(){
        views.apply {
            toggleAudioDevice.setOnClickListener {
                if (isSpeakerMode){
                    //we should set it to earpiece mode
                    toggleAudioDevice.setImageResource(R.drawable.ic_speaker)
                    //we should send a command to our service to switch between devices
                    serviceRepository.toggleAudioDevice(RTCAudioManager.AudioDevice.EARPIECE.name)

                }else{
                    //we should set it to speaker mode
                    toggleAudioDevice.setImageResource(R.drawable.ic_ear)
                    serviceRepository.toggleAudioDevice(RTCAudioManager.AudioDevice.SPEAKER_PHONE.name)

                }
                isSpeakerMode = !isSpeakerMode
            }

        }
    }

    private fun setupCameraToggleClicked(){
        views.apply {
            toggleCameraButton.setOnClickListener {
                if (!isCameraMuted){
                    serviceRepository.toggleVideo(true)
                    toggleCameraButton.setImageResource(R.drawable.ic_camera_on)
                }else{
                    serviceRepository.toggleVideo(false)
                    toggleCameraButton.setImageResource(R.drawable.ic_camera_off)
                }

                isCameraMuted = !isCameraMuted
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // DON'T re-init views here!
        // Just verify they're still assigned
        Log.d("CallActivity", "onResume - views status:")
        Log.d("CallActivity", "  Remote view: ${MainService.remoteSurfaceView}")
        Log.d("CallActivity", "  Local view: ${MainService.localSurfaceView}")
        Log.d("CallActivity", "  Initialized: $viewsInitialized")
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.d("CallActivity", "onDestroy - releasing views")

        // Release views
        MainService.remoteSurfaceView?.release()
        MainService.remoteSurfaceView = null

        MainService.localSurfaceView?.release()
        MainService.localSurfaceView = null

        viewsInitialized = false
    }

    override fun onCallEnded() {
        finish()
    }
}