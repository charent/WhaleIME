package com.whale.ime.config

/**
 * 随包 Rime 词库数据的版本号（`yyyyMMddHHmm`，一天可打多版且不产生歧义）。
 *
 * **必须递增此版本号**，否则已安装的设备不会重新复制 assets 里的词典与配置
 * （见 `boot/rime/RimeDataInstaller`）。
 */
object DictDataVersion {

    // 202610260900：词频修复第一步——pinyin.table.bin 占位权重降档。ext/tencent 全表权重是
    //           占位值 100（rime-ice 上游无频次），压住了 base 里 33% 真实词频在 1~100 的常用词，
    //           导致低频词抢首选。将 133 万条 ln(100)=4.605 的占位条目原地改为 log(ε)=-36.04
    //           （无权重档），排在一切真实词频之后、base 查不到时仍出候选（覆盖不丢）。
    //           改法：docs/prototype/patch_table_weights.py 直改二进制（ps1 管线也加了源级降权）
    // 202611071219：词库整体替换为 rime-frost（白霜拼音，gaboolic/rime-frost 2026-11-07 版）。
    //           雾凇的 ext/tencent 共 133 万条占位权重（100）是词频失真的根因；白霜用 7.45 亿字
    //           语料重新分词统计字频/词频并归一化，各表 weight==100 仅占 0.1%（真实词频）。
    //           只取核心表 8105+base+ext+others+corrections（约 61 万条），不引入 tencent /
    //           41448 / GB18030 / 细胞词库；pinyin.table.bin 由 60.6MB 降到 18.1MB。
    //           同时重编 t9_pinyin.prism.bin（词典 checksum 变了，过期 prism 会失配导致九键零候选）。
    //           占位档降权逻辑已从 precompile-dict.ps1 移除（白霜无需降权）。
    // 202611071245：默认启用白霜全部 23 个 cn_dicts_cell 细胞词库（长尾补充）。实测对日常
    //           词 top1/top5 零影响（top1 99%、top5 100%，过半词条权重为 0 不抢首选），
    //           只把成语/地名/人名/历史/医药/游戏/体育等长尾词从 >20 名提到 1~6 名。
    //           代价：pinyin.table.bin 18.1MB -> 59.6MB，APK 约 +40MB。
    //           想瘦身：precompile-dict.ps1 -WithCells:$false（纯核心 18MB）或
    //           -CellOnly idiom place name2 geography history exthot industry_product（+3.5MB）。
    // 202611081200：下线乱序17输入：删除 assets/rime 里的 double_pinyin_ls17 schema 与 build 产物，
    //           default.yaml 的 schema_list 同步移除（升级设备会重拷一次词库）
    // 202611102246：补录白霜「增频表落空」的漏词：是的 在白霜 others/增频词.txt 里标了 +1000，
    //           但词条被核心表剪枝删掉（只剩 literature 细胞表 weight=0，等于垫底），
    //           于是 shi'd 的首选是时代/使得/时的，是的 打不出来。新增 cn_dicts/supplement 一张表，
    //           按「同码锚定」把 rime-ice 的词频换算到本桶量纲（是的 33131，介于时代 46509 与
    //           使得 28413 之间），pinyin.table.bin / t9_pinyin.prism.bin 同步重编。
    // 202611141600：english 词典补源并重编。此前 english.table.bin / english.prism.bin 是无源
    //           预编译产物，词表内容无从重建；现由 scripts/precompile-dict.ps1 汇总 rime-frost 的
    //           en_dicts（en + en_ext）生成 name: english 后再编，并为含大写的 code 派生一条
    //           小写码（GPU → 额外一条 gpu），小写输入直接命中 —— 检索大小写不敏感。
    // 202611142000：emoji 联想补词。opencc/emoji.txt 加 vs/VS/Vs（此前只有带点的 `Vs.`，
    //           小写 vs 的 echo 候选匹配不上，🆚 永远不出现）；补 66 条常用中文词
    //           （晚安/生日快乐/加班/摸鱼/可乐…）与 59 条常用英文词（thanks/sorry/love…），
    //           英文键按词库原文大小写照写（LOL/LMAO/OMG/BTW 在 en 表里是大写）。
    //           english.schema.yaml 同时补 simplifier@emoji（英文方案此前完全无联想）。
    // 202611142130：english 表补 vs / VS 词条（precompile-dict.ps1 新增内置表
    //           en_dicts/whale_supplement）。只加 emoji.txt 不够：输入 vs 被当成拼音串后
    //           script_translator 的纠错候选先占满候选表，echo_translator 的兜底候选
    //           （文本 = 输入串）当场自毁（EchoTranslation::Compare），simplifier@emoji
    //           根本没有宿主候选 —— 现象是候选区只有「于是/女生/趋势」这类纠错词。
    //           english.table.bin / english.prism.bin 随之重编。
    // 202611142200：英文候选排序与派生收口。① 生成 english 词典时只给 text 与 code 同形的
    //           条目派生小写码 —— en_ext 里 700+ 条「全称 → 缩写」不再派生，敲 gpu 的首选
    //           回到 GPU，而不是 "Graphics Processing Unit"；② 中文形态下，只有当输入能被
    //           **完整拼写**解释（存在 comment 与输入相同的汉字候选）时，原样命中的英文词条
    //           才后移 —— nu 不再被英文 nu 与希腊字母 ν 抢首位，ok（欧克是简拼 ou ke）与
    //           code（打不出精确汉字）行为不变。
    // 202611211200：新增 14 键拼音方案 t14_pinyin（QW/ER/TY/UI/OP · AS/DF/GH/JK/L ·
    //           ZX/CV/BN/M）。与九键共用 pinyin.table.bin，但拼写集合是 14 键键码
    //           （大写，各键首字母），由 precompile-dict.ps1 单独编出 t14_pinyin.prism.bin。
    //           schema 的 derive 段与 Kotlin 侧 T14KeyMap 均由 gen-t14-algebra.py 生成。
    const val CURRENT_RIME_DICT_DATA_VERSIOM = 202611211200
}
