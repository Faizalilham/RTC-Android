package dev.faizal.rtcandroid.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import javax.inject.Inject

class MainServiceRepository @Inject constructor(
    private val context: Context
) {

    fun startService(username:String){
        Thread{
            val intent = Intent(context, MainService::class.java)
            intent.putExtra("username",username)
            intent.action = MainServiceActions.START_SERVICE.name
            startServiceIntent(intent)
        }.start()
    }

    private fun startServiceIntent(intent: Intent){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            context.startForegroundService(intent)
        }else{
            context.startService(intent)
        }
    }

    fun setupViews(isVideoCall: Boolean, isCaller: Boolean, target: String) {
        Log.d("MainServiceRepository", "setupViews called - isVideoCall: $isVideoCall, isCaller: $isCaller, target: $target")

        val intent = Intent(context, MainService::class.java).apply {
            action = MainServiceActions.SETUP_VIEWS.name
            putExtra("isVideoCall", isVideoCall)
            putExtra("isCaller", isCaller)
            putExtra("target", target)
        }

        context.startService(intent)
        Log.d("MainServiceRepository", "SETUP_VIEWS intent sent to MainService")
    }

    fun sendEndCall() {
        val intent = Intent(context, MainService::class.java)
        intent.action = MainServiceActions.END_CALL.name
        startServiceIntent(intent)
    }

    fun switchCamera() {
        val intent = Intent(context, MainService::class.java)
        intent.action = MainServiceActions.SWITCH_CAMERA.name
        startServiceIntent(intent)
    }

    fun toggleAudio(shouldBeMuted: Boolean) {
        val intent = Intent(context, MainService::class.java)
        intent.action = MainServiceActions.TOGGLE_AUDIO.name
        intent.putExtra("shouldBeMuted",shouldBeMuted)
        startServiceIntent(intent)
    }

    fun toggleVideo(shouldBeMuted: Boolean) {
        val intent = Intent(context, MainService::class.java)
        intent.action = MainServiceActions.TOGGLE_VIDEO.name
        intent.putExtra("shouldBeMuted",shouldBeMuted)
        startServiceIntent(intent)
    }

    fun toggleAudioDevice(type: String) {
        val intent = Intent(context, MainService::class.java)
        intent.action = MainServiceActions.TOGGLE_AUDIO_DEVICE.name
        intent.putExtra("type",type)
        startServiceIntent(intent)
    }

    fun toggleScreenShare(isStarting: Boolean) {
        val intent = Intent(context, MainService::class.java)
        intent.action = MainServiceActions.TOGGLE_SCREEN_SHARE.name
        intent.putExtra("isStarting",isStarting)
        startServiceIntent(intent)
    }

    fun stopService() {
        val intent = Intent(context, MainService::class.java)
        intent.action = MainServiceActions.STOP_SERVICE.name
        startServiceIntent(intent)
    }

}