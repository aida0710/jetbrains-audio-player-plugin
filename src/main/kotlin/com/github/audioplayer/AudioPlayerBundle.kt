package com.github.audioplayer

import com.intellij.DynamicBundle
import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.PropertyKey

@NonNls
private const val BUNDLE = "messages.AudioPlayerBundle"

// DynamicBundle は継承せず、インスタンスを持って委譲する（継承用のコンストラクタは非推奨）。
// 渡すクラスのクラスローダーから文言ファイルを探す。
object AudioPlayerBundle {
    private val bundle = DynamicBundle(AudioPlayerBundle::class.java, BUNDLE)

    @Nls
    fun message(
        @PropertyKey(resourceBundle = BUNDLE) key: String,
        vararg params: Any,
    ): String = bundle.getMessage(key, *params)
}
