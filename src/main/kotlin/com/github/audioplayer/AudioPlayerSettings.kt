package com.github.audioplayer

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@State(
    name = "AudioPlayerSettings",
    storages = [Storage("AudioPlayerSettings.xml")],
)
class AudioPlayerSettings : PersistentStateComponent<AudioPlayerSettings.SettingsState> {
    data class SettingsState(
        var ffmpegPath: String = "",
        var ffprobePath: String = "",
        var lastVolume: Int = 80,
        var lastLooping: Boolean = false,
        var defaultView: String = "waveform",
        var showVisualizer: Boolean = true,
        var waveformSplitChannels: Boolean = false,
    )

    private var myState = SettingsState()

    override fun getState(): SettingsState = myState

    override fun loadState(state: SettingsState) {
        myState = state
    }

    companion object {
        val instance: AudioPlayerSettings
            get() = ApplicationManager.getApplication().getService(AudioPlayerSettings::class.java)
    }
}
