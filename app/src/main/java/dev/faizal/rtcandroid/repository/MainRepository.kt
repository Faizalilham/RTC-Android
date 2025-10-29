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
                Log.d(TAG, "🎥 REMOTE VIDEO TRACK RECEIVED - Setting up...")

                Handler(Looper.getMainLooper()).post {
                    try {
                        if (remoteView == null) {
                            Log.w(TAG, "Remote view not ready, storing track")
                            pendingRemoteVideoTrack = videoTrack
                            return@post
                        }

                        Log.d(TAG, "Adding video sink to remote view...")

                        // Clear any existing track first
                        remoteView?.let { view ->
                            // Remove any existing sinks
                            videoTrack.removeSink(view)

                            // Add the new sink
                            videoTrack.setEnabled(true)
                            videoTrack.addSink(view)

                            // Force the view to update
                            view.visibility = android.view.View.VISIBLE
                            view.requestLayout()
                            view.invalidate()

                            Log.d(TAG, "✅ REMOTE VIDEO TRACK CONNECTED TO VIEW")
                        }

                    } catch (e: Exception) {
                        Log.e(TAG, "❌ ERROR setting up remote video track", e)
                    }
                }
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
        webRTCClient.initRemoteSurfaceView(view)
        this.remoteView = view
        Log.d(TAG, "Remote surface view initialized: $view")

        // If we received remote video track before view was ready, add it now
        pendingRemoteVideoTrack?.let { videoTrack ->
            Log.d(TAG, "⏰ Found pending remote video track, adding it now...")
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    videoTrack.addSink(view)
                    Log.d(TAG, "✅ SUCCESS: Pending video track added to remote view!")
                    view.visibility = android.view.View.VISIBLE
                    view.requestLayout()
                    view.invalidate()
                    pendingRemoteVideoTrack = null
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error adding pending video track", e)
                }
            }, 100) // Small delay to ensure view is ready
        }
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