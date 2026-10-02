package de.corespace.shroud.core.calls.media

import android.content.Context
import android.media.AudioAttributes
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * One peer-connection factory and one [EglBase] for the process (calls §5). Created on the first
 * engine, shared by a loopback pair, and kept after [de.corespace.shroud.core.calls.CallMediaEngine.close].
 *
 * The call UI is handed [EglBase.getEglBaseContext] once, when the engine is attached. Releasing
 * that context at the end of a call would leave the next call drawing with a dead context, so
 * close drops the peer connection and the capturers only. iOS keeps its factory the same way.
 */
internal object WebRtcRuntime {
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var audio: JavaAudioDeviceModule? = null
    private var users = 0

    data class Handle(val egl: EglBase, val factory: PeerConnectionFactory)

    @Synchronized
    fun acquire(context: Context): Handle {
        val existingEgl = egl
        val existingFactory = factory
        if (existingEgl != null && existingFactory != null) {
            users++
            return Handle(existingEgl, existingFactory)
        }
        val app = context.applicationContext
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions(),
        )
        val base = EglBase.create()
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .createAudioDeviceModule()
        val created = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(base.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(base.eglBaseContext))
            .createPeerConnectionFactory()
        egl = base
        factory = created
        audio = adm
        users = 1
        return Handle(base, created)
    }

    /** Drops one user. The factory and EGL context go away only when the last engine does. */
    @Synchronized
    fun release() {
        if (users <= 0) return
        users--
        if (users > 0) return
        try {
            factory?.dispose()
        } catch (_: RuntimeException) {
        }
        factory = null
        try {
            audio?.release()
        } catch (_: RuntimeException) {
        }
        audio = null
        try {
            egl?.release()
        } catch (_: RuntimeException) {
        }
        egl = null
    }
}
