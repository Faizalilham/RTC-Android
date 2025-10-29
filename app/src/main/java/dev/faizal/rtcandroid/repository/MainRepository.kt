package dev.faizal.rtcandroid.repository

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.faizal.rtcandroid.firebaseClient.FirebaseClient
import dev.faizal.rtcandroid.utils.DataModel
import dev.faizal.rtcandroid.utils.DataModelType.*
import dev.faizal.rtcandroid.utils.UserStatus
import dev.faizal.rtcandroid.webrtc.MyPeerObserver
import dev.faizal.rtcandroid.webrtc.WebRTCClient
import com.google.gson.Gson
import org.webrtc.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MainRepository @Inject constructor(
    private val firebaseClient: FirebaseClient,
    private val webRTCClient: WebRTCClient,
    private val gson: Gson
) : WebRTCClient.Listener {

    private val TAG = "MainRepository"
    private var target: String? = null
    var listener: Listener? = null
    private var remoteView: SurfaceViewRenderer? = null

    // Store remote tracks that arrive before view is ready
    private var pendingRemoteVideoTrack: VideoTrack? = null

    fun login(username: String, password: String, isDone: (Boolean, String?) -> Unit) {
        firebaseClient.login(username, password, isDone)
    }

    fun observeUsersStatus(status: (List<Pair<String, String>>) -> Unit) {
        firebaseClient.observeUsersStatus(status)
    }

    fun initFirebase() {
        firebaseClient.subscribeForLatestEvent(object : FirebaseClient.Listener {
            override fun onLatestEventReceived(event: DataModel) {
                listener?.onLatestEventReceived(event)
                when (event.type) {
                    Offer -> {
                        webRTCClient.onRemoteSessionReceived(
                            SessionDescription(
                                SessionDescription.Type.OFFER,
                                event.data.toString()
                            )
                        )
                        webRTCClient.answer(target!!)
                    }
                    Answer -> {
                        webRTCClient.onRemoteSessionReceived(
                            SessionDescription(
                                SessionDescription.Type.ANSWER,
                                event.data.toString()
                            )
                        )
                    }
                    IceCandidates -> {
                        val candidate: IceCandidate? = try {
                            gson.fromJson(event.data.toString(), IceCandidate::class.java)
                        } catch (e: Exception) {
                            null
                        }
                        candidate?.let {
                            webRTCClient.addIceCandidateToPeer(it)
                        }
                    }
                    EndCall -> {
                        listener?.endCall()
                    }
                    else -> Unit
                }
            }

        })
    }

    fun sendConnectionRequest(target: String, isVideoCall: Boolean, success: (Boolean) -> Unit) {
        firebaseClient.sendMessageToOtherClient(
            DataModel(
                type = if (isVideoCall) StartVideoCall else StartAudioCall,
                target = target
            ), success
        )
    }

    fun setTarget(target: String) {
        this.target = target
    }

    interface Listener {
        fun onLatestEventReceived(data: DataModel)
        fun endCall()
    }

    fun initWebrtcClient(username: String) {
        webRTCClient.listener = this
        webRTCClient.initializeWebrtcClient(username, object : MyPeerObserver() {

            override fun onIceCandidate(p0: IceCandidate?) {
                super.onIceCandidate(p0)
                p0?.let {
                    webRTCClient.sendIceCandidate(target!!, it)
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                super.onConnectionChange(newState)
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                    // 1. change my status to in call
                    changeMyStatus(UserStatus.IN_CALL)
                    // 2. clear latest event inside my user section in firebase database
                    firebaseClient.clearLatestEvent()
                }
            }

            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                super.onAddTrack(receiver, streams)
                Log.d(TAG, "========== onAddTrack START ==========")
                Log.d(TAG, "Receiver: $receiver")
                Log.d(TAG, "Streams count: ${streams?.size}")

                val track = receiver?.track()
                if (track == null) {
                    Log.e(TAG, "❌ ERROR: track is null")
                    Log.d(TAG, "========== onAddTrack END (FAILED) ==========")
                    return
                }

                Log.d(TAG, "✅ Track received:")
                Log.d(TAG, "  - Kind: ${track.kind()}")
                Log.d(TAG, "  - ID: ${track.id()}")
                Log.d(TAG, "  - Enabled: ${track.enabled()}")
                Log.d(TAG, "  - State: ${track.state()}")

                when (track.kind()) {
                    MediaStreamTrack.VIDEO_TRACK_KIND -> {
                        Log.d(TAG, "🎥 This is a VIDEO track")
                        val videoTrack = track as VideoTrack
                        videoTrack.setEnabled(true)
                        Log.d(TAG, "Video track enabled, calling onRemoteVideoTrackReceived...")
                        onRemoteVideoTrackReceived(videoTrack)
                    }
                    MediaStreamTrack.AUDIO_TRACK_KIND -> {
                        Log.d(TAG, "🔊 This is an AUDIO track")
                        val audioTrack = track as AudioTrack
                        audioTrack.setEnabled(true)
                        onRemoteAudioTrackReceived(audioTrack)
                    }
                    else -> {
                        Log.w(TAG, "⚠️ Unknown track kind: ${track.kind()}")
                    }
                }
                Log.d(TAG, "========== onAddTrack END ==========")
            }

            override fun onRemoteVideoTrackReceived(videoTrack: VideoTrack) {
                Log.d(TAG, "========== DIAGNOSTIC TEST START ==========")

                // TEST 1: Cek track properties
                Log.d(TAG, "Track ID: ${videoTrack.id()}")
                Log.d(TAG, "Track Kind: ${videoTrack.kind()}")
                Log.d(TAG, "Track State: ${videoTrack.state()}")
                Log.d(TAG, "Track Enabled: ${videoTrack.enabled()}")

                // TEST 2: Enable track explicitly
                videoTrack.setEnabled(true)
                Log.d(TAG, "Track enabled: ${videoTrack.enabled()}")

                // TEST 3: Cek remote view
                Log.d(TAG, "Remote View: $remoteView")
                Log.d(TAG, "Remote View null? ${remoteView == null}")

                if (remoteView == null) {
                    Log.e(TAG, "❌ Remote view is NULL!")
                    pendingRemoteVideoTrack = videoTrack
                    return
                }

                // TEST 4: Cek view properties
                Log.d(TAG, "View Width: ${remoteView?.width}")
                Log.d(TAG, "View Height: ${remoteView?.height}")
                Log.d(TAG, "View Visibility: ${remoteView?.visibility}")
                Log.d(TAG, "View Parent: ${remoteView?.parent}")

                // TEST 5: Try adding sink with multiple attempts
                Handler(Looper.getMainLooper()).post {
                    var attempts = 0
                    fun tryAddSink() {
                        attempts++
                        try {
                            Log.d(TAG, "Attempt $attempts: Adding video sink...")

                            // Clear any existing sinks first
                            // (create a test sink to verify track is working)
                            val testSink = object : VideoSink {
                                override fun onFrame(frame: VideoFrame?) {
                                    // If this gets called, track is definitely sending frames
                                    Log.d(TAG, "🎬 VIDEO FRAME RECEIVED! Size: ${frame?.buffer?.width}x${frame?.buffer?.height}")
                                }
                            }

                            videoTrack.addSink(testSink)
                            Log.d(TAG, "✅ Test sink added - waiting for frames...")

                            // Wait a bit then add to real view
                            Handler(Looper.getMainLooper()).postDelayed({
                                try {
                                    videoTrack.removeSink(testSink)
                                    videoTrack.addSink(remoteView)

                                    remoteView?.visibility = android.view.View.VISIBLE
                                    remoteView?.requestLayout()
                                    remoteView?.invalidate()

                                    Log.d(TAG, "✅ SUCCESS: Video sink added to remote view!")

                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ Error adding to remote view: ${e.message}", e)

                                    if (attempts < 3) {
                                        Log.d(TAG, "Retrying in 500ms...")
                                        Handler(Looper.getMainLooper()).postDelayed({
                                            tryAddSink()
                                        }, 500)
                                    }
                                }
                            }, 1000)

                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Attempt $attempts failed: ${e.message}", e)

                            if (attempts < 3) {
                                Log.d(TAG, "Retrying in 500ms...")
                                Handler(Looper.getMainLooper()).postDelayed({
                                    tryAddSink()
                                }, 500)
                            }
                        }
                    }

                    tryAddSink()
                }

                Log.d(TAG, "========== DIAGNOSTIC TEST END ==========")
            }

            override fun onRemoteAudioTrackReceived(audioTrack: AudioTrack) {
                Log.d(TAG, "Remote audio track received and will play automatically")
            }
        })
    }

    fun initLocalSurfaceView(view: SurfaceViewRenderer, isVideoCall: Boolean) {
        webRTCClient.initLocalSurfaceView(view, isVideoCall)
    }

    fun setupRemoteViewFirst() {
        // Pastikan remote view sudah siap sebelum track datang
        remoteView?.let { view ->
            Handler(Looper.getMainLooper()).post {
                view.visibility = android.view.View.VISIBLE
                view.requestLayout()
            }
        }
    }

    fun initRemoteSurfaceView(view: SurfaceViewRenderer) {
        Log.d(TAG, "========== initRemoteSurfaceView START ==========")

        // Clean up if view was already initialized
        this.remoteView?.let { oldView ->
            try {
                oldView.release()
                Log.d(TAG, "Released old remote view")
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing old view: $e")
            }
        }

        this.remoteView = view

        // Re-initialize view di UI thread
        Handler(Looper.getMainLooper()).post {
            webRTCClient.initRemoteSurfaceView(view)
            Log.d(TAG, "WebRTC remote view initialized")

            // Jika ada pending track, tambahkan sekarang
            pendingRemoteVideoTrack?.let { videoTrack ->
                Log.d(TAG, "⏰ Processing pending video track...")

                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        Log.d(TAG, "Adding pending track to view...")
                        videoTrack.setEnabled(true)
                        videoTrack.addSink(view)

                        view.visibility = android.view.View.VISIBLE
                        view.requestLayout()

                        Log.d(TAG, "✅ Pending track added successfully!")
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error adding pending track", e)
                        e.printStackTrace()
                    }
                }, 500) // Delay untuk memastikan view fully ready
            }
        }

        Log.d(TAG, "Remote surface view reference stored: $view")
        Log.d(TAG, "========== initRemoteSurfaceView END ==========")
    }

    fun startCall() {
        webRTCClient.call(target!!)
    }

    fun endCall() {
        webRTCClient.closeConnection()
        changeMyStatus(UserStatus.ONLINE)
    }

    fun sendEndCall() {
        onTransferEventToSocket(
            DataModel(
                type = EndCall,
                target = target!!
            )
        )
    }

    private fun changeMyStatus(status: UserStatus) {
        firebaseClient.changeMyStatus(status)
    }

    fun toggleAudio(shouldBeMuted: Boolean) {
        webRTCClient.toggleAudio(shouldBeMuted)
    }

    fun toggleVideo(shouldBeMuted: Boolean) {
        webRTCClient.toggleVideo(shouldBeMuted)
    }

    fun switchCamera() {
        webRTCClient.switchCamera()
    }

    override fun onTransferEventToSocket(data: DataModel) {
        firebaseClient.sendMessageToOtherClient(data) {}
    }

    fun setScreenCaptureIntent(screenPermissionIntent: Intent) {
        webRTCClient.setPermissionIntent(screenPermissionIntent)
    }

    fun toggleScreenShare(isStarting: Boolean) {
        if (isStarting) {
            webRTCClient.startScreenCapturing()
        } else {
            webRTCClient.stopScreenCapturing()
        }
    }

    fun logOff(function: () -> Unit) = firebaseClient.logOff(function)

}