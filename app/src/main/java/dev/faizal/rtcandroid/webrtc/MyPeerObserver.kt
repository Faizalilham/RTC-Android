package dev.faizal.rtcandroid.webrtc

import android.util.Log
import org.webrtc.*

/**
 * Observer untuk PeerConnection yang menangani event WebRTC
 * File ini harus ada di MainRepository atau tempat Anda membuat PeerConnection
 */
abstract class MyPeerObserver : PeerConnection.Observer {

    private val TAG = "PeerConnectionObserver"

    override fun onSignalingChange(newState: PeerConnection.SignalingState?) {
        Log.d(TAG, "onSignalingChange: $newState")
    }

    override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
        Log.d(TAG, "onIceConnectionChange: $newState")
        when(newState) {
            PeerConnection.IceConnectionState.CONNECTED -> {
                Log.d(TAG, "ICE Connected - streams should now flow")
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                Log.w(TAG, "ICE Disconnected")
            }
            PeerConnection.IceConnectionState.FAILED -> {
                Log.e(TAG, "ICE Connection Failed")
            }
            PeerConnection.IceConnectionState.CLOSED -> {
                Log.d(TAG, "ICE Connection Closed")
            }
            else -> {}
        }
    }

    override fun onIceConnectionReceivingChange(receiving: Boolean) {
        Log.d(TAG, "onIceConnectionReceivingChange: $receiving")
    }

    override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
        Log.d(TAG, "onIceGatheringChange: $newState")
    }

    override fun onIceCandidate(iceCandidate: IceCandidate?) {
        Log.d(TAG, "onIceCandidate: ${iceCandidate?.sdp}")
    }

    override fun onIceCandidatesRemoved(iceCandidates: Array<out IceCandidate>?) {
        Log.d(TAG, "onIceCandidatesRemoved: ${iceCandidates?.size}")
    }

    override fun onAddStream(stream: MediaStream?) {
        Log.d(TAG, "onAddStream (deprecated): videoTracks=${stream?.videoTracks?.size}, audioTracks=${stream?.audioTracks?.size}")
        // This is deprecated but might still be called in some cases
        // For modern WebRTC, use onTrack instead
    }

    override fun onRemoveStream(stream: MediaStream?) {
        Log.d(TAG, "onRemoveStream: $stream")
    }

    override fun onDataChannel(dataChannel: DataChannel?) {
        Log.d(TAG, "onDataChannel: ${dataChannel?.label()}")
    }

    override fun onRenegotiationNeeded() {
        Log.d(TAG, "onRenegotiationNeeded")
    }

    override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
        Log.d(TAG, "onAddTrack: receiver=$receiver, streams=${streams?.size}")
    }

    override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
        Log.d(TAG, "onConnectionChange: $newState")
        when(newState) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                Log.d(TAG, "Peer Connection CONNECTED")
            }
            PeerConnection.PeerConnectionState.FAILED -> {
                Log.e(TAG, "Peer Connection FAILED")
            }
            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                Log.w(TAG, "Peer Connection DISCONNECTED")
            }
            else -> {}
        }
    }

    /**
     * CRITICAL: Method ini yang menerima remote tracks (video/audio)
     * Ini adalah cara modern untuk menerima stream dari remote peer
     */
    override fun onTrack(transceiver: RtpTransceiver?) {
        Log.d(TAG, "onTrack called")

        val track = transceiver?.receiver?.track()
        if (track == null) {
            Log.e(TAG, "onTrack: track is null")
            return
        }

        Log.d(TAG, "onTrack: kind=${track.kind()}, id=${track.id()}, enabled=${track.enabled()}")

        when (track.kind()) {
            MediaStreamTrack.VIDEO_TRACK_KIND -> {
                Log.d(TAG, "Remote VIDEO track received")
                val videoTrack = track as VideoTrack
                videoTrack.setEnabled(true)
                onRemoteVideoTrackReceived(videoTrack)
            }
            MediaStreamTrack.AUDIO_TRACK_KIND -> {
                Log.d(TAG, "Remote AUDIO track received")
                val audioTrack = track as AudioTrack
                audioTrack.setEnabled(true)
                onRemoteAudioTrackReceived(audioTrack)
            }
            else -> {
                Log.w(TAG, "Unknown track kind: ${track.kind()}")
            }
        }
    }

    /**
     * Override method ini untuk menangani remote video track
     */
    abstract fun onRemoteVideoTrackReceived(videoTrack: VideoTrack)

    /**
     * Override method ini untuk menangani remote audio track (opsional)
     */
    open fun onRemoteAudioTrackReceived(audioTrack: AudioTrack) {
        // Audio biasanya otomatis diputar, tidak perlu handling khusus
        Log.d(TAG, "Remote audio track received and enabled")
    }
}