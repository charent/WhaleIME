package com.whale.ime.engine.schema

/**
 * 由 `ime-native/native/scripts/gen-t14-algebra.py` 生成，勿手改。
 *
 * 键码 = 14 键各键首字母大写（QW→Q、ER→E…M→M），与 schema 的 derive 段、
 * KeyboardData.layoutT14Cn 的键位一一对应。音节表与九键同源（414 个音节）。
 *
 * 字符串格式（运行时在 [T14KeyUtils] / [CorrectionSchema] 里解析，避免生成
 * 上千行 mapOf）：
 * - [CHAR_TO_KEY]：两字符一组「小写字母 + 键码」；
 * - [SYLLABLES]：`;` 分隔的「音节 键码」；
 * - [FUZZY] / [V_AS_U]：`;` 分隔的「键码 from>键码 to」，供
 *   CorrectionSchema 写入 `t14_pinyin.custom.yaml` 的 speller/algebra/+。
 */
object T14KeyMap {
    const val CHAR_TO_KEY = "qQwQeErEtTyTuUiUoOpOaAsAdDfDgGhGjJkJlLzZxZcCvCbBnBmM"

    const val SYLLABLES =
        "a A;ai AU;an AB;ang ABG;ao AO;ba BA;" +
        "bai BAU;ban BAB;bang BABG;bao BAO;bei BEU;ben BEB;" +
        "beng BEBG;bi BU;bian BUAB;biang BUABG;biao BUAO;bie BUE;" +
        "bin BUB;bing BUBG;bo BO;bu BU;ca CA;cai CAU;" +
        "can CAB;cang CABG;cao CAO;ce CE;cei CEU;cen CEB;" +
        "ceng CEBG;cha CGA;chai CGAU;chan CGAB;chang CGABG;chao CGAO;" +
        "che CGE;chen CGEB;cheng CGEBG;chi CGU;chong CGOBG;chou CGOU;" +
        "chu CGU;chua CGUA;chuai CGUAU;chuan CGUAB;chuang CGUABG;chui CGUU;" +
        "chun CGUB;chuo CGUO;ci CU;cong COBG;cou COU;cu CU;" +
        "cuan CUAB;cui CUU;cun CUB;cuo CUO;da DA;dai DAU;" +
        "dan DAB;dang DABG;dao DAO;de DE;dei DEU;den DEB;" +
        "deng DEBG;di DU;dia DUA;dian DUAB;diao DUAO;die DUE;" +
        "ding DUBG;diu DUU;dong DOBG;dou DOU;du DU;duan DUAB;" +
        "dui DUU;dun DUB;duo DUO;e E;ei EU;en EB;" +
        "eng EBG;er EE;fa DA;fan DAB;fang DABG;fei DEU;" +
        "fen DEB;feng DEBG;fiao DUAO;fo DO;fou DOU;fu DU;" +
        "ga GA;gai GAU;gan GAB;gang GABG;gao GAO;ge GE;" +
        "gei GEU;gen GEB;geng GEBG;gong GOBG;gou GOU;gu GU;" +
        "gua GUA;guai GUAU;guan GUAB;guang GUABG;gui GUU;gun GUB;" +
        "guo GUO;ha GA;hai GAU;han GAB;hang GABG;hao GAO;" +
        "he GE;hei GEU;hen GEB;heng GEBG;hong GOBG;hou GOU;" +
        "hu GU;hua GUA;huai GUAU;huan GUAB;huang GUABG;hui GUU;" +
        "hun GUB;huo GUO;ji JU;jia JUA;jian JUAB;jiang JUABG;" +
        "jiao JUAO;jie JUE;jin JUB;jing JUBG;jiong JUOBG;jiu JUU;" +
        "ju JU;juan JUAB;jue JUE;jun JUB;ka JA;kai JAU;" +
        "kan JAB;kang JABG;kao JAO;ke JE;kei JEU;ken JEB;" +
        "keng JEBG;kong JOBG;kou JOU;ku JU;kua JUA;kuai JUAU;" +
        "kuan JUAB;kuang JUABG;kui JUU;kun JUB;kuo JUO;la LA;" +
        "lai LAU;lan LAB;lang LABG;lao LAO;le LE;lei LEU;" +
        "leng LEBG;li LU;lia LUA;lian LUAB;liang LUABG;liao LUAO;" +
        "lie LUE;lin LUB;ling LUBG;liu LUU;lo LO;long LOBG;" +
        "lou LOU;lu LU;luan LUAB;lun LUB;luo LUO;lv LC;" +
        "lve LCE;ma MA;mai MAU;man MAB;mang MABG;mao MAO;" +
        "me ME;mei MEU;men MEB;meng MEBG;mi MU;mian MUAB;" +
        "miao MUAO;mie MUE;min MUB;ming MUBG;miu MUU;mo MO;" +
        "mou MOU;mu MU;na BA;nai BAU;nan BAB;nang BABG;" +
        "nao BAO;ne BE;nei BEU;nen BEB;neng BEBG;ni BU;" +
        "nian BUAB;niang BUABG;niao BUAO;nie BUE;nin BUB;ning BUBG;" +
        "niu BUU;nong BOBG;nou BOU;nu BU;nuan BUAB;nuo BUO;" +
        "nv BC;nve BCE;o O;ou OU;pa OA;pai OAU;" +
        "pan OAB;pang OABG;pao OAO;pei OEU;pen OEB;peng OEBG;" +
        "pi OU;pian OUAB;piao OUAO;pie OUE;pin OUB;ping OUBG;" +
        "po OO;pou OOU;pu OU;qi QU;qia QUA;qian QUAB;" +
        "qiang QUABG;qiao QUAO;qie QUE;qin QUB;qing QUBG;qiong QUOBG;" +
        "qiu QUU;qu QU;quan QUAB;que QUE;qun QUB;ran EAB;" +
        "rang EABG;rao EAO;re EE;ren EEB;reng EEBG;ri EU;" +
        "rong EOBG;rou EOU;ru EU;rua EUA;ruan EUAB;rui EUU;" +
        "run EUB;ruo EUO;sa AA;sai AAU;san AAB;sang AABG;" +
        "sao AAO;se AE;sen AEB;seng AEBG;sha AGA;shai AGAU;" +
        "shan AGAB;shang AGABG;shao AGAO;she AGE;shei AGEU;shen AGEB;" +
        "sheng AGEBG;shi AGU;shou AGOU;shu AGU;shua AGUA;shuai AGUAU;" +
        "shuan AGUAB;shuang AGUABG;shui AGUU;shun AGUB;shuo AGUO;si AU;" +
        "song AOBG;sou AOU;su AU;suan AUAB;sui AUU;sun AUB;" +
        "suo AUO;ta TA;tai TAU;tan TAB;tang TABG;tao TAO;" +
        "te TE;tei TEU;teng TEBG;ti TU;tian TUAB;tiao TUAO;" +
        "tie TUE;ting TUBG;tong TOBG;tou TOU;tu TU;tuan TUAB;" +
        "tui TUU;tun TUB;tuo TUO;wa QA;wai QAU;wan QAB;" +
        "wang QABG;wei QEU;wen QEB;weng QEBG;wo QO;wu QU;" +
        "xi ZU;xia ZUA;xian ZUAB;xiang ZUABG;xiao ZUAO;xie ZUE;" +
        "xin ZUB;xing ZUBG;xiong ZUOBG;xiu ZUU;xu ZU;xuan ZUAB;" +
        "xue ZUE;xun ZUB;ya TA;yan TAB;yang TABG;yao TAO;" +
        "ye TE;yi TU;yin TUB;ying TUBG;yo TO;yong TOBG;" +
        "you TOU;yu TU;yuan TUAB;yue TUE;yun TUB;za ZA;" +
        "zai ZAU;zan ZAB;zang ZABG;zao ZAO;ze ZE;zei ZEU;" +
        "zen ZEB;zeng ZEBG;zha ZGA;zhai ZGAU;zhan ZGAB;zhang ZGABG;" +
        "zhao ZGAO;zhe ZGE;zhei ZGEU;zhen ZGEB;zheng ZGEBG;zhi ZGU;" +
        "zhong ZGOBG;zhou ZGOU;zhu ZGU;zhua ZGUA;zhuai ZGUAU;zhuan ZGUAB;" +
        "zhuang ZGUABG;zhui ZGUU;zhun ZGUB;zhuo ZGUO;zi ZU;zong ZOBG;" +
        "zou ZOU;zu ZU;zuan ZUAB;zui ZUU;zun ZUB;zuo ZUO"

    const val FUZZY =
        "AA>AGA;AAB>AABG;AAB>AGAB;AABG>AAB;AABG>AGABG;AAO>AGAO;" +
        "AAU>AGAU;AB>ABG;ABG>AB;AE>AGE;AEB>AEBG;AEB>AGEB;" +
        "AEBG>AEB;AEBG>AGEBG;AGA>AA;AGAB>AAB;AGAB>AGABG;AGABG>AABG;" +
        "AGABG>AGAB;AGAO>AAO;AGAU>AAU;AGE>AE;AGEB>AEB;AGEB>AGEBG;" +
        "AGEBG>AEBG;AGEBG>AGEB;AGEU>AEU;AGOU>AOU;AGU>AU;AGUA>AUA;" +
        "AGUAB>AGUABG;AGUAB>AUAB;AGUABG>AGUAB;AGUABG>AUABG;AGUAU>AUAU;AGUB>AUB;" +
        "AGUO>AUO;AGUU>AUU;AOBG>AGOBG;AOU>AGOU;AU>AGU;AUAB>AGUAB;" +
        "AUAB>AUABG;AUB>AGUB;AUO>AGUO;AUU>AGUU;BA>LA;BAB>BABG;" +
        "BAB>LAB;BABG>BAB;BABG>LABG;BAO>LAO;BAU>LAU;BC>LC;" +
        "BCE>LCE;BE>LE;BEB>BEBG;BEB>LEB;BEBG>BEB;BEBG>LEBG;" +
        "BEU>LEU;BOBG>LOBG;BOU>LOU;BU>LU;BUAB>BUABG;BUAB>LUAB;" +
        "BUABG>BUAB;BUABG>LUABG;BUAO>LUAO;BUB>BUBG;BUB>LUB;BUBG>BUB;" +
        "BUBG>LUBG;BUE>LUE;BUO>LUO;BUU>LUU;CA>CGA;CAB>CABG;" +
        "CAB>CGAB;CABG>CAB;CABG>CGABG;CAO>CGAO;CAU>CGAU;CE>CGE;" +
        "CEB>CEBG;CEB>CGEB;CEBG>CEB;CEBG>CGEBG;CEU>CGEU;CGA>CA;" +
        "CGAB>CAB;CGAB>CGABG;CGABG>CABG;CGABG>CGAB;CGAO>CAO;CGAU>CAU;" +
        "CGE>CE;CGEB>CEB;CGEB>CGEBG;CGEBG>CEBG;CGEBG>CGEB;CGOBG>COBG;" +
        "CGOU>COU;CGU>CU;CGUA>CUA;CGUAB>CGUABG;CGUAB>CUAB;CGUABG>CGUAB;" +
        "CGUABG>CUABG;CGUAU>CUAU;CGUB>CUB;CGUO>CUO;CGUU>CUU;COBG>CGOBG;" +
        "COU>CGOU;CU>CGU;CUAB>CGUAB;CUAB>CUABG;CUB>CGUB;CUO>CGUO;" +
        "CUU>CGUU;DA>GA;DAB>DABG;DAB>GAB;DABG>DAB;DABG>GABG;" +
        "DEB>DEBG;DEB>GEB;DEBG>DEB;DEBG>GEBG;DEU>GEU;DO>GO;" +
        "DOU>GOU;DU>GU;DUAB>DUABG;DUAO>GUAO;DUBG>DUB;EAB>EABG;" +
        "EAB>LAB;EABG>EAB;EABG>LABG;EAO>LAO;EB>EBG;EBG>EB;" +
        "EE>LE;EEB>EEBG;EEB>LEB;EEBG>EEB;EEBG>LEBG;EOBG>LOBG;" +
        "EOU>LOU;EU>LU;EUA>LUA;EUAB>EUABG;EUAB>LUAB;EUB>LUB;" +
        "EUO>LUO;EUU>LUU;GA>DA;GAB>DAB;GAB>GABG;GABG>DABG;" +
        "GABG>GAB;GAO>DAO;GAU>DAU;GE>DE;GEB>DEB;GEB>GEBG;" +
        "GEBG>DEBG;GEBG>GEB;GEU>DEU;GOBG>DOBG;GOU>DOU;GU>DU;" +
        "GUA>DUA;GUAB>DUAB;GUAB>GUABG;GUABG>DUABG;GUABG>GUAB;GUAU>DUAU;" +
        "GUB>DUB;GUO>DUO;GUU>DUU;JAB>JABG;JABG>JAB;JEB>JEBG;" +
        "JEBG>JEB;JUAB>JUABG;JUABG>JUAB;JUB>JUBG;JUBG>JUB;LA>BA;" +
        "LA>EA;LAB>BAB;LAB>EAB;LAB>LABG;LABG>BABG;LABG>EABG;" +
        "LABG>LAB;LAO>BAO;LAO>EAO;LAU>BAU;LAU>EAU;LC>BC;" +
        "LC>EC;LCE>BCE;LCE>ECE;LE>BE;LE>EE;LEBG>BEBG;" +
        "LEBG>EEBG;LEBG>LEB;LEU>BEU;LEU>EEU;LO>BO;LO>EO;" +
        "LOBG>BOBG;LOBG>EOBG;LOU>BOU;LOU>EOU;LU>BU;LU>EU;" +
        "LUA>BUA;LUA>EUA;LUAB>BUAB;LUAB>EUAB;LUAB>LUABG;LUABG>BUABG;" +
        "LUABG>EUABG;LUABG>LUAB;LUAO>BUAO;LUAO>EUAO;LUB>BUB;LUB>EUB;" +
        "LUB>LUBG;LUBG>BUBG;LUBG>EUBG;LUBG>LUB;LUE>BUE;LUE>EUE;" +
        "LUO>BUO;LUO>EUO;LUU>BUU;LUU>EUU;MAB>MABG;MABG>MAB;" +
        "MEB>MEBG;MEBG>MEB;MUAB>MUABG;MUB>MUBG;MUBG>MUB;OAB>OABG;" +
        "OABG>OAB;OEB>OEBG;OEBG>OEB;OUAB>OUABG;OUB>OUBG;OUBG>OUB;" +
        "QAB>QABG;QABG>QAB;QEB>QEBG;QEBG>QEB;QUAB>QUABG;QUABG>QUAB;" +
        "QUB>QUBG;QUBG>QUB;TAB>TABG;TABG>TAB;TEBG>TEB;TUAB>TUABG;" +
        "TUB>TUBG;TUBG>TUB;ZA>ZGA;ZAB>ZABG;ZAB>ZGAB;ZABG>ZAB;" +
        "ZABG>ZGABG;ZAO>ZGAO;ZAU>ZGAU;ZE>ZGE;ZEB>ZEBG;ZEB>ZGEB;" +
        "ZEBG>ZEB;ZEBG>ZGEBG;ZEU>ZGEU;ZGA>ZA;ZGAB>ZAB;ZGAB>ZGABG;" +
        "ZGABG>ZABG;ZGABG>ZGAB;ZGAO>ZAO;ZGAU>ZAU;ZGE>ZE;ZGEB>ZEB;" +
        "ZGEB>ZGEBG;ZGEBG>ZEBG;ZGEBG>ZGEB;ZGEU>ZEU;ZGOBG>ZOBG;ZGOU>ZOU;" +
        "ZGU>ZU;ZGUA>ZUA;ZGUAB>ZGUABG;ZGUAB>ZUAB;ZGUABG>ZGUAB;ZGUABG>ZUABG;" +
        "ZGUAU>ZUAU;ZGUB>ZUB;ZGUO>ZUO;ZGUU>ZUU;ZOBG>ZGOBG;ZOU>ZGOU;" +
        "ZU>ZGU;ZUAB>ZGUAB;ZUAB>ZUABG;ZUABG>ZUAB;ZUB>ZGUB;ZUB>ZUBG;" +
        "ZUBG>ZUB;ZUO>ZGUO;ZUU>ZGUU"

    const val V_AS_U =
        "JU>JC;JUAB>JCAB;JUB>JCB;JUE>JCE;QU>QC;QUAB>QCAB;" +
        "QUB>QCB;QUE>QCE;TU>TC;TUAB>TCAB;TUB>TCB;TUE>TCE;" +
        "ZU>ZC;ZUAB>ZCAB;ZUB>ZCB;ZUE>ZCE"
}
