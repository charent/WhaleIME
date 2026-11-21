package com.whale.ime.storage

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.whale.ime.boot.Launcher
import com.whale.ime.preference.AppPrefs
import com.whale.ime.preference.model.KeyboardMenuMode
import com.whale.ime.storage.dao.ClipboardDao
import com.whale.ime.storage.dao.PhraseDao
import com.whale.ime.storage.dao.SideSymbolDao
import com.whale.ime.storage.dao.SkbFunDao
import com.whale.ime.storage.dao.UsedSymbolDao
import com.whale.ime.storage.dao.UserWordDao
import com.whale.ime.storage.entity.Clipboard
import com.whale.ime.storage.entity.Phrase
import com.whale.ime.storage.entity.SideSymbol
import com.whale.ime.storage.entity.SkbFun
import com.whale.ime.storage.entity.UsedSymbol
import com.whale.ime.storage.entity.UserWord
import com.whale.ime.storage.migration.Migrations
import com.whale.ime.util.thread.ThreadPoolUtils

@Database(
    entities = [SideSymbol::class, Clipboard::class, UsedSymbol::class, Phrase::class, SkbFun::class, UserWord::class],
    version = 1,
    exportSchema = false
)
abstract class WhaleDatabase : RoomDatabase() {
    abstract fun sideSymbolDao(): SideSymbolDao
    abstract fun clipboardDao(): ClipboardDao
    abstract fun usedSymbolDao(): UsedSymbolDao
    abstract fun phraseDao(): PhraseDao
    abstract fun skbFunDao(): SkbFunDao
    abstract fun userWordDao(): UserWordDao

    companion object {

        /**
         * 功能宫格的展示顺序（4 列宫格，即 `position`），每 4 项一行。
         *
         * 行的含义：输入面板 → 键盘模式与外观 → 文字转换与工具栏 → 设置。
         * 定制页拖拽排序写回的也是这张表的 `position`，所以这里是「重置为默认顺序」的唯一来源。
         */
        internal val MENU_ORDER = listOf(
            // 输入面板
            KeyboardMenuMode.Emojicon,        // 表情输入
            KeyboardMenuMode.ClipBoard,       // 剪贴板
            KeyboardMenuMode.TextEdit,        // 文本编辑
            KeyboardMenuMode.Phrases,         // 常用语
            // 键盘模式与外观
            KeyboardMenuMode.SwitchKeyboard,  // 切换键盘
            KeyboardMenuMode.DarkTheme,       // 深色模式
            KeyboardMenuMode.FloatKeyboard,   // 悬浮键盘
            KeyboardMenuMode.KeyboardHeight,  // 键盘调节
            // 文字转换与工具栏
            KeyboardMenuMode.OneHanded,       // 单手模式
            KeyboardMenuMode.JianFan,         // 繁体输入
            KeyboardMenuMode.FlowerTypeface,  // 火星文
            KeyboardMenuMode.Custom,          // 定制工具栏
            // 设置（固定最后）
            KeyboardMenuMode.Settings,
        )

        /** 拼音侧边栏默认符号（自上而下） */
        internal val DEFAULT_SIDE_SYMBOLS_PINYIN =
            listOf("，", "。", "？", "！", "……", "：", "；", ".")

        /** 数字侧边栏默认符号（自上而下） */
        internal val DEFAULT_SIDE_SYMBOLS_NUMBER =
            listOf("+", "-", "*", "/", "=", "%", "^", "@", "#")

        private fun defaultSideSymbols(): List<SideSymbol> = buildList {
            DEFAULT_SIDE_SYMBOLS_PINYIN.forEach { add(SideSymbol(it)) }
            DEFAULT_SIDE_SYMBOLS_NUMBER.forEach { add(SideSymbol(it, "number")) }
        }

        val instance = Room.databaseBuilder(Launcher.instance.context, WhaleDatabase::class.java, "ime_db")
            .allowMainThreadQueries()
            .addMigrations(*Migrations.ALL)
            // 库版本是 1（见 Migrations）：磁盘上的库版本对不上时直接重建表，
            // 而不是抛 "A migration from X to 1 was required but not found" 让输入法启动即崩。
            // 设置项在 SharedPreferences 里不受影响，只有词库/剪贴板/常用语这类表数据会重置。
            .fallbackToDestructiveMigration()
            .addCallback(object : Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    super.onOpen(db)
                    ThreadPoolUtils.executeSingleton {
                        seedIfEmpty()
                    }
                }
            })
            .build()

        /**
         * 常用语种子版本：每次往 [DEFAULT_PHRASES] 追加条目就 +1。
         *
         * 为什么不能沿用「表为空才写」：老用户表里早就有 5 条，新增的短句永远补不进去。
         * 也不能用 REPLACE 覆盖：会把用户改过的快捷码、置顶状态一起冲掉。
         * 所以按内容主键做 INSERT IGNORE——只补缺的，一行都不动。
         */
        private const val PHRASE_SEED_VERSION = 2

        /**
         * 常用语种子：都是「打开就能用」的高频短句。
         *
         * 每条都带首字母快捷码（`qwerty`）和对应的九键码（`t9`），只在键盘「常用语」
         * 面板里编辑/展示；常用语不参与候选上屏，避免内置短句抢占 `zm` 这类常见编码。
         * 内容为主键，所以改文案等于新增一条，旧文案请保留以免老用户重复。
         */
        private val DEFAULT_PHRASES = listOf(
            // 通话 / 位置（历史 5 条，保留原文案）
            Phrase(content = "我的电话是__，常联系。", t9 = "9334", qwerty = "wddh"),
            Phrase(content = "抱歉，我现在不方便接电话，稍后联系。", t9 = "2799", qwerty = "bqwx"),
            Phrase(content = "我正在开会，有急事请发短信。", t9 = "9995", qwerty = "wzzk"),
            Phrase(content = "我很快就到，请稍微等一会儿。", t9 = "9455", qwerty = "whkj"),
            Phrase(content = "麻烦放驿站，谢谢。", t9 = "6339", qwerty = "mffy"),
            Phrase(content = "我在开会，稍后回复你。", t9 = "99", qwerty = "wz"),
            Phrase(content = "收到，马上处理。", t9 = "73", qwerty = "sd"),
            Phrase(content = "文件已发你邮箱，请查收。", t9 = "95", qwerty = "wj"),
            Phrase(content = "这个方案我再确认一下，稍后答复。", t9 = "94", qwerty = "zg"),
            Phrase(content = "会议改到下午三点，请准时参加。", t9 = "49", qwerty = "hy"),
            // 问候 / 日常
            Phrase(content = "你好，很高兴认识你。", t9 = "64", qwerty = "nh"),
            Phrase(content = "早上好，新的一天开始啦！", t9 = "974", qwerty = "zsh"),
            Phrase(content = "晚安，好梦。", t9 = "92", qwerty = "wa"),
            Phrase(content = "好久不见，最近怎么样？", t9 = "45", qwerty = "hj"),
            Phrase(content = "在吗？有事找你。", t9 = "96", qwerty = "zm"),
            Phrase(content = "今天有点事，晚点联系。", t9 = "58", qwerty = "jt"),
            Phrase(content = "我已经在路上了，马上到。", t9 = "95", qwerty = "wj"),
            Phrase(content = "到楼下了，麻烦开下门。", t9 = "35", qwerty = "dl"),
            Phrase(content = "到家了吗？路上小心。", t9 = "35", qwerty = "dj"),
            Phrase(content = "记得带伞，今天有雨。", t9 = "53", qwerty = "jd"),
            Phrase(content = "吃饭了吗？", t9 = "23", qwerty = "cf"),
            Phrase(content = "注意身体，早点休息。", t9 = "99", qwerty = "zy"),
            // 快递 / 收货
            Phrase(content = "快递放门口就行，谢谢。", t9 = "53", qwerty = "kd"),
            Phrase(content = "麻烦放快递柜，谢谢。", t9 = "6353", qwerty = "mfkd"),
            Phrase(content = "麻烦帮我改一下收货地址。", t9 = "63", qwerty = "mf"),
            // 致谢 / 致歉 / 应答
            Phrase(content = "谢谢，辛苦了！", t9 = "99", qwerty = "xx"),
            Phrase(content = "不好意思，打扰一下。", t9 = "24", qwerty = "bh"),
            Phrase(content = "抱歉，是我弄错了。", t9 = "27", qwerty = "bq"),
            Phrase(content = "非常感谢你的帮助！", t9 = "32", qwerty = "fc"),
            Phrase(content = "没问题，交给我。", t9 = "98", qwerty = "wt"),
            Phrase(content = "好的，没问题。", t9 = "47", qwerty = "hd"),
            Phrase(content = "可以，就这么定。", t9 = "59", qwerty = "ky"),
            // 祝福
            Phrase(content = "生日快乐，心想事成！", t9 = "77", qwerty = "sr"),
            Phrase(content = "新年快乐，万事如意！", t9 = "96", qwerty = "xn"),
            Phrase(content = "恭喜恭喜，前程似锦！", t9 = "49", qwerty = "gx"),
            Phrase(content = "一路顺风，注意安全！", t9 = "95", qwerty = "yl"),
            Phrase(content = "身体健康，阖家幸福！", t9 = "78", qwerty = "st"),
            // 网购 / 客服
            Phrase(content = "已付款，请尽快发货。", t9 = "93", qwerty = "yf"),
            Phrase(content = "请问什么时候发货？", t9 = "79", qwerty = "qw"),
            Phrase(content = "收到货了，很满意，谢谢！", t9 = "73", qwerty = "sd"),
            Phrase(content = "可以再便宜一点吗？", t9 = "59", qwerty = "ky"),
            // 口语 / 情绪
            Phrase(content = "哈哈哈哈，笑死我了。", t9 = "44", qwerty = "hh"),
            Phrase(content = "说得对，我同意。", t9 = "73", qwerty = "sd"),
            Phrase(content = "好家伙，真没想到。", t9 = "45", qwerty = "hj"),
            Phrase(content = "我裂开了。", t9 = "955", qwerty = "wlk"),
            Phrase(content = "无语。", t9 = "99", qwerty = "wy"),
            Phrase(content = "太厉害了！", t9 = "85", qwerty = "tl"),
            Phrase(content = "今晚有空吗？一起吃饭。", t9 = "59", qwerty = "jw"),
            Phrase(content = "我堵车了，可能晚到十分钟。", t9 = "32", qwerty = "dc"),
        )

        /** 按种子版本幂等地补齐常用语；已存在的行（含用户改过的快捷码）不动 */
        private fun seedPhrases() {
            val version = runCatching { AppPrefs.getInstance().internal.phraseSeedVersion }.getOrNull()
                ?: return
            if (version.getValue() >= PHRASE_SEED_VERSION) return
            runCatching {
                instance.phraseDao().insertIgnore(DEFAULT_PHRASES)
                version.setValue(PHRASE_SEED_VERSION)
            }
        }

        /**
         * 补齐默认数据。每次开库都跑一遍，但每一步都先判空/按版本号 gate，
         * 所以正常情况下只有几次极轻的查询：
         * - 侧边栏符号：表里一个都没有才写默认（用户删光某一类时另一类还在，不会误判）；
         * - 功能宫格与工具栏：整表为空才写默认；
         * - 常用语：按 [PHRASE_SEED_VERSION] 幂等补缺。
         */
        private fun seedIfEmpty() {  //初始化数据库数据
            if (instance.sideSymbolDao().getAllSideSymbolPinyin().isEmpty()) {
                instance.sideSymbolDao().insertAll(defaultSideSymbols())
            }
            seedPhrases()
            if(instance.skbFunDao().getAllMenu().isEmpty()) {
                val skbFuns = buildList {
                    // 常驻工具栏默认：切换输入法、剪贴板、表情输入、文本编辑（position 即工具栏顺序）
                    add(SkbFun(name = KeyboardMenuMode.SwitchKeyboard.name, isKeep = 1, position = 0))
                    add(SkbFun(name = KeyboardMenuMode.ClipBoard.name, isKeep = 1, position = 1))
                    add(SkbFun(name = KeyboardMenuMode.Emojicon.name, isKeep = 1, position = 2))
                    add(SkbFun(name = KeyboardMenuMode.TextEdit.name, isKeep = 1, position = 3))
                    // 功能宫格：position 即 [MENU_ORDER] 里的下标
                    MENU_ORDER.forEachIndexed { index, mode ->
                        add(SkbFun(name = mode.name, isKeep = 0, position = index))
                    }
                }
                instance.skbFunDao().insertAll(skbFuns)
            }
        }
    }
}
