package io.homeassistant.companion.android.assist.bluetooth

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
internal abstract class HeadsetVoiceModule {

    // Unscoped: each Assist screen owns its own session
    @Binds
    abstract fun headsetVoiceSession(impl: HeadsetVoiceSessionImpl): HeadsetVoiceSession

    @Binds
    abstract fun headsetPlatform(impl: AndroidHeadsetPlatform): HeadsetPlatform
}
