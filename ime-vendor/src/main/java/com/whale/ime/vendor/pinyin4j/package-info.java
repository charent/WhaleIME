/**
 * pinyin4j 的本地副本，来源 http://sourceforge.net/projects/pinyin4j/ ，
 * 许可证：GNU GPL v2 or later（见各文件头）。
 *
 * 上游版本：上游未标注版本（pinyin4j 2.5.0 是最后一个发布版，本项目文件已大幅改动，无法逐行比对确认）。
 *
 * 本地改动（升级前必读，直接覆盖会全部丢失）：
 * - ResourceHelper 不再读 classpath 资源，改由 AssetManager 注入（PinyinHelper.init），
 *   表文件位于 ime-native 的 assets/pinyindb/{unicode_to_hanyu_pinyin,multi_pinyin}.txt；
 * - PinyinHelper 重写：只保留 getPinYinHeadChar / toHanYuPinyin / toHanYuPinyinMulti 三个入口，
 *   删除拼音→汉字方向的 API；
 * - multipinyin/Trie：多音字联想用的前缀树，非上游 pinyin4j 原文件。
 */
package com.whale.ime.vendor.pinyin4j;
