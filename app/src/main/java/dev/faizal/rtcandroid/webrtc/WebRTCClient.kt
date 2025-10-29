    package dev.faizal.rtcandroid.webrtc

    import android.content.Context
    import android.content.Intent
    import android.media.projection.MediaProjection
    import android.util.DisplayMetrics
    import android.util.Log
    import android.view.WindowManager
    import dev.faizal.rtcandroid.utils.DataModel
    import dev.faizal.rtcandroid.utils.DataModelType
    import com.google.gson.Gson
    import org.webrtc.*
    import javax.inject.Inject
    import javax.inject.Singleton

    @Singleton
    class WebRTCClient @Inject constructor(
        private val context: Context,
        private val gson: Gson
    ) {
        //class variables
        var listener: Listener? = null
        private lateinit var username: String

        //webrtc variables
        private val eglBaseContext = EglBase.create().eglBaseContext
        private val peerConnectionFactory by lazy { createPeerConnectionFactory() }
        private var peerConnection: PeerConnection? = null
        private val iceServer = listOf(
            PeerConnection.IceServer.builder("turn:relay1.expressturn.com:3480")
                .setUsername("000000002077020938")
                .setPassword("q5WiEwLFVKFkhran4WUpdWfjBnA=").createIceServer()
        )

        private val localVideoSource by lazy { peerConnectionFactory.createVideoSource(false) }
        private val localAudioSource by lazy { peerConnectionFactory.createAudioSource(MediaConstraints())}
        private val videoCapturer = getVideoCapturer(context)
        private var surfaceTextureHelper:SurfaceTextureHelper?=null
        private val mediaConstraint = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo","true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio","true"))
        }

        //call variables
        private lateinit var localSurfaceView: SurfaceViewRenderer
        private lateinit var remoteSurfaceView: SurfaceViewRenderer
        private var localStream: MediaStream? = null
        private var localTrackId = ""
        private var localStreamId = ""
        private var localAudioTrack:AudioTrack?=null
        private var localVideoTrack:VideoTrack?=null

        //screen casting
        private var permissionIntent:Intent?=null
        private var screenCapturer:VideoCapturer?=null
        private val localScreenVideoSource by lazy { peerConnectionFactory.createVideoSource(false) }
        private var localScreenShareVideoTrack:VideoTrack?=null

        //installing requirements section
        init {
            initPeerConnectionFactory()
        }
        private fun initPeerConnectionFactory() {
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true).setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
        }
        private fun createPeerConnectionFactory(): PeerConnectionFactory {
            return PeerConnectionFactory.builder()
                .setVideoDecoderFactory(
                    DefaultVideoDecoderFactory(eglBaseContext)
                ).setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(
                        eglBaseContext, true, true
                    )
                ).setOptions(PeerConnectionFactory.Options().apply {
                    disableNetworkMonitor = false
                    disableEncryption = false
                }).createPeerConnectionFactory()
        }
        fun initializeWebrtcClient(
            username: String, observer: PeerConnection.Observer
        ) {
            this.username = username
            localTrackId = "${username}_track"
            localStreamId = "${username}_stream"
            peerConnection = createPeerConnection(observer)
        }
        private fun createPeerConnection(observer: PeerConnection.Observer): PeerConnection? {
            val rtcConfig = PeerConnection.RTCConfiguration(iceServer).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            }
            return peerConnectionFactory.createPeerConnection(rtcConfig, observer)
        }

        //negotiation section
        fun call(target:String){
            peerConnection?.createOffer(object : MySdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    super.onCreateSuccess(desc)
                    peerConnection?.setLocalDescription(object : MySdpObserver() {
                        override fun onSetSuccess() {
                            super.onSetSuccess()
                            listener?.onTransferEventToSocket(
                                DataModel(type = DataModelType.Offer,
                                    sender = username,
                                    target = target,
                                    data = desc?.description)
                            )
                        }
                    },desc)
                }
            },mediaConstraint)
        }

        fun answer(target:String){
            peerConnection?.createAnswer(object : MySdpObserver() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    super.onCreateSuccess(desc)
                    peerConnection?.setLocalDescription(object : MySdpObserver() {
                        override fun onSetSuccess() {
                            super.onSetSuccess()
                            listener?.onTransferEventToSocket(
                                DataModel(type = DataModelType.Answer,
                                    sender = username,
                                    target = target,
                                    data = desc?.description)
                            )
                        }
                    },desc)
                }
            },mediaConstraint)
        }

        fun onRemoteSessionReceived(sessionDescription: SessionDescription){
            peerConnection?.setRemoteDescription(MySdpObserver(),sessionDescription)
        }

        fun addIceCandidateToPeer(iceCandidate: IceCandidate){
            peerConnection?.addIceCandidate(iceCandidate)
        }

        fun sendIceCandidate(target: String,iceCandidate: IceCandidate){
            addIceCandidateToPeer(iceCandidate)
            listener?.onTransferEventToSocket(
                DataModel(
                    type = DataModelType.IceCandidates,
                    sender = username,
                    target = target,
                    data = gson.toJson(iceCandidate)
                )
            )
        }

        fun closeConnection(){
            try {
                videoCapturer.dispose()
                screenCapturer?.dispose()
                localStream?.dispose()
                peerConnection?.close()
            }catch (e:Exception){
                e.printStackTrace()
            }
        }

        fun switchCamera(){
            videoCapturer.switchCamera(null)
        }

        fun toggleAudio(shouldBeMuted:Boolean){
            if (shouldBeMuted){
                localAudioTrack?.setEnabled(false)
            }else{
                localAudioTrack?.setEnabled(true)
            }
        }

        fun toggleVideo(shouldBeMuted: Boolean){
            try {
                if (shouldBeMuted){
                    stopCapturingCamera()
                }else{
                    startCapturingCamera(localSurfaceView)
                }
            }catch (e:Exception){
                e.printStackTrace()
            }
        }

        //streaming section
        private fun initSurfaceView(view: SurfaceViewRenderer) {
            view.run {
                setMirror(false)
                setEnableHardwareScaler(true)
                init(eglBaseContext, null)
            }
        }

        fun initRemoteSurfaceView(view: SurfaceViewRenderer) {
            Log.d("WebRTCClient", "========== initRemoteSurfaceView START ==========")

            this.remoteSurfaceView = view

            try {
                view.run {
                    // CRITICAL: Release dulu jika sudah di-init sebelumnya
                    try {
                        release()
                        Log.d("WebRTCClient", "Released previous init")
                    } catch (e: Exception) {
                        // First init, no problem
                    }

                    // Set properties SEBELUM init
                    setMirror(false)
                    setEnableHardwareScaler(true)

                    // PENTING: Remote view HARUS di bawah
                    setZOrderMediaOverlay(false)
                    setZOrderOnTop(false)

                    // Init dengan EGL context
                    init(eglBaseContext, null)

                    // Set visible
                    visibility = android.view.View.VISIBLE

                    Log.d("WebRTCClient", "Remote view initialized successfully:")
                    Log.d("WebRTCClient", "  - Width: ${view.width}, Height: ${view.height}")
                    Log.d("WebRTCClient", "  - Visibility: ${view.visibility}")
                    Log.d("WebRTCClient", "  - HW Accelerated: ${view.isHardwareAccelerated}")
                }

                Log.d("WebRTCClient", "✅ Remote view setup complete")

            } catch (e: Exception) {
                Log.e("WebRTCClient", "❌ Error initializing remote view", e)
                e.printStackTrace()
            }

            Log.d("WebRTCClient", "========== initRemoteSurfaceView END ==========")
        }

        fun initLocalSurfaceView(localView: SurfaceViewRenderer, isVideoCall: Boolean) {
            this.localSurfaceView = localView
            localView.run {
                setMirror(true)
                setEnableHardwareScaler(true)
                setZOrderMediaOverlay(true)
                setZOrderOnTop(true)
                init(eglBaseContext, null)
            }
            Log.d("WebRTCClient", "Local view initialized with z-order on top")
            startLocalStreaming(localView, isVideoCall)
        }
        private fun startLocalStreaming(localView: SurfaceViewRenderer, isVideoCall: Boolean) {
            localStream = peerConnectionFactory.createLocalMediaStream(localStreamId)

            if (isVideoCall){
                startCapturingCamera(localView)
            }

            // Create audio track
            localAudioTrack = peerConnectionFactory.createAudioTrack(localTrackId+"_audio",localAudioSource)
            localStream?.addTrack(localAudioTrack)

            // FIXED: Use addTrack instead of addStream
            localAudioTrack?.let { audioTrack ->
                peerConnection?.addTrack(audioTrack, listOf(localStreamId))
            }

            // Video track will be added in startCapturingCamera if isVideoCall is true
        }

        private fun startCapturingCamera(localView: SurfaceViewRenderer){
            surfaceTextureHelper = SurfaceTextureHelper.create(
                Thread.currentThread().name,eglBaseContext
            )

            videoCapturer.initialize(
                surfaceTextureHelper,context,localVideoSource.capturerObserver
            )

            videoCapturer.startCapture(
                720,480,20
            )

            localVideoTrack = peerConnectionFactory.createVideoTrack(localTrackId+"_video",localVideoSource)
            localVideoTrack?.addSink(localView)
            localStream?.addTrack(localVideoTrack)

            // FIXED: Use addTrack instead of addStream
            localVideoTrack?.let { videoTrack ->
                peerConnection?.addTrack(videoTrack, listOf(localStreamId))
            }
        }

        private fun getVideoCapturer(context: Context):CameraVideoCapturer =
            Camera2Enumerator(context).run {
                deviceNames.find {
                    isFrontFacing(it)
                }?.let {
                    createCapturer(it,null)
                }?:throw IllegalStateException()
            }

        private fun stopCapturingCamera(){
            videoCapturer.dispose()
            localVideoTrack?.removeSink(localSurfaceView)
            localSurfaceView.clearImage()
            localStream?.removeTrack(localVideoTrack)
            localVideoTrack?.dispose()
        }

        //screen capture section

        fun setPermissionIntent(screenPermissionIntent: Intent) {
            this.permissionIntent = screenPermissionIntent
        }

        fun startScreenCapturing() {
            val displayMetrics = DisplayMetrics()
            val windowsManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowsManager.defaultDisplay.getMetrics(displayMetrics)

            val screenWidthPixels = displayMetrics.widthPixels
            val screenHeightPixels = displayMetrics.heightPixels

            val surfaceTextureHelper = SurfaceTextureHelper.create(
                Thread.currentThread().name,eglBaseContext
            )

            screenCapturer = createScreenCapturer()
            screenCapturer!!.initialize(
                surfaceTextureHelper,context,localScreenVideoSource.capturerObserver
            )
            screenCapturer!!.startCapture(screenWidthPixels,screenHeightPixels,15)

            localScreenShareVideoTrack =
                peerConnectionFactory.createVideoTrack(localTrackId+"_screen",localScreenVideoSource)
            localScreenShareVideoTrack?.addSink(localSurfaceView)
            localStream?.addTrack(localScreenShareVideoTrack)

            // FIXED: Use addTrack instead of addStream
            localScreenShareVideoTrack?.let { screenTrack ->
                peerConnection?.addTrack(screenTrack, listOf(localStreamId))
            }
        }

        fun stopScreenCapturing() {
            screenCapturer?.stopCapture()
            screenCapturer?.dispose()
            localScreenShareVideoTrack?.removeSink(localSurfaceView)
            localSurfaceView.clearImage()
            localStream?.removeTrack(localScreenShareVideoTrack)
            localScreenShareVideoTrack?.dispose()
        }

        private fun createScreenCapturer():VideoCapturer {
            return ScreenCapturerAndroid(permissionIntent, object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.d("permissions", "onStop: permission of screen casting is stopped")
                }
            })
        }


        interface Listener {
            fun onTransferEventToSocket(data: DataModel)
        }
    }