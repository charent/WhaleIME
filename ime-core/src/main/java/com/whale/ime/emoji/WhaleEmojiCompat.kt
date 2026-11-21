package com.whale.ime.emoji

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.view.inputmethod.EditorInfo
import androidx.emoji2.text.DefaultEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object WhaleEmojiCompat {
    var mEditorInfo:EditorInfo? = null
    private var metadataVersion: Int = 0
    private var replaceAll: Boolean = false
    private lateinit var instanceNoReplace: InstanceHandler
    private lateinit var instanceReplaceAll: InstanceHandler

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Initialize this helper and its EmojiCompat instances with given [context]. Immediately begins loading the emoji
     * metadata in a background thread. After this method has been called, it is safe to call [getAsFlow].
     */
    fun init(context: Context) {
        instanceNoReplace = InstanceHandler(context, replaceAll = false)
        instanceReplaceAll = InstanceHandler(context, replaceAll = true)

        scope.launch {
            instanceNoReplace.load()
        }
        scope.launch {
            instanceReplaceAll.load()
        }
    }

    fun setEditorInfo(editorInfo: EditorInfo?) {
        this.mEditorInfo = editorInfo
        metadataVersion = editorInfo?.extras?.getInt(EmojiCompat.EDITOR_INFO_METAVERSION_KEY, 0) ?: 0
        replaceAll = editorInfo?.extras?.getBoolean(EmojiCompat.EDITOR_INFO_REPLACE_ALL_KEY, false) ?: false
    }

    /**
     * Gets the current EmojiCompat instance based on [replaceAll] and sets it as the default instance if
     * [setAsDefaultInstance] is true. Calling this method before [init] will cause an exception to be thrown.
     *
     * @return A state flow providing the latest EmojiCompat instance for given args. The flow may provide null if
     *  EmojiCompat is still loading or if it has failed.
     */
    @SuppressLint("RestrictedApi")
    fun getAsFlow(setAsDefaultInstance: Boolean = true): StateFlow<EmojiCompat?> {
        val instanceFlow = if (replaceAll) {
            instanceReplaceAll.publishedInstanceFlow
        } else {
            instanceNoReplace.publishedInstanceFlow
        }
        val instance = instanceFlow.value
        if (setAsDefaultInstance && instance != null) {
            // This API is not really supposed to be used by third-party apps, but it is really handy and does
            // exactly what we need, so we suppress the restriction here
            EmojiCompat.reset(instance)
        }
        return instanceFlow
    }

    private class InstanceHandler(context: Context, replaceAll: Boolean = false) {
        private val initCallback: EmojiCompat.InitCallback = object : EmojiCompat.InitCallback() {
            override fun onInitialized() {
                super.onInitialized()
                publishedInstanceFlow.value = instance
            }
        }

        private val config: EmojiCompat.Config? = DefaultEmojiCompatConfig.create(context)?.apply {
            setReplaceAll(replaceAll)
            setMetadataLoadStrategy(EmojiCompat.LOAD_STRATEGY_MANUAL)
            registerInitCallback(initCallback)
        }

        // Despite its name, `EmojiCompat.reset()` actually creates a new instance, exactly what we need
        private val instance: EmojiCompat? = if (config != null) EmojiCompat.reset(config) else null
        val publishedInstanceFlow = MutableStateFlow<EmojiCompat?>(null)

        /**
         * Manually loads the EmojiCompat instance. Call this method on a background thread to avoid blocking main.
         *
         * @see EmojiCompat.load
         */
        fun load() {
            instance?.load()
        }
    }
}

/**
 * 随包发布的 emoji 字体（`assets/fonts/NotoColorEmojiCompat.ttf`，Noto Color Emoji / Emoji 18.0）。
 *
 * 为什么自带字体而不吃系统字体：系统 emoji 字体随 Android 版本冻结（minSdk 33 的 Android 13 只到
 * Emoji 14，Android 16 才到 16.0），Emoji 16/17/18 的表情在多数在网机型上根本画不出来，
 * 只能整段从面板里剔掉——这是「表情缺失」的真正原因。自带字体后表情面板与设备无关。
 *
 * 与 [WhaleEmojiCompat] 的分工：那套 EmojiCompat 只负责和宿主编辑器协商元数据（`mEditorInfo`），
 * 面板渲染走这里，避免两套字体混排。
 *
 * 注意：这里只提供平台 [Typeface]；Compose 的 `FontFamily` 封装在 UI 层完成，
 * core 不引入任何 `androidx.compose.*`。
 */
object EmojiFont {
    const val ASSET_PATH = "fonts/NotoColorEmojiCompat.ttf"

    /**
     * 表情面板用的字体。加载失败返回 null（例如裁剪了字体资源），调用方回退系统字体：
     * 那时新表情会缺字形，但面板本身不会崩。
     */
    fun typeface(context: Context): Typeface? =
        runCatching { Typeface.createFromAsset(context.assets, ASSET_PATH) }.getOrNull()

    /** 启动时在子线程预热：10MB 字体首次解析约几十毫秒，别留给面板第一次展开 */
    fun prewarm(context: Context) {
        typeface(context)
    }
}
