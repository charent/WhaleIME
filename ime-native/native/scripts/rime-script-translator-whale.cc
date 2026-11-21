//
// Copyright RIME Developers
// Distributed under the BSD License
//
// Script translator
//
// 2011-07-10 GONG Chen <chen.sst@gmail.com>
//
#include <algorithm>
#include <stack>
#include <cmath>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <boost/algorithm/string/join.hpp>
#include <boost/range/adaptor/reversed.hpp>
#include <rime/common.h>
#include <rime/composition.h>
#include <rime/candidate.h>
#include <rime/config.h>
#include <rime/context.h>
#include <rime/engine.h>
#include <rime/language.h>
#include <rime/schema.h>
#include <rime/translation.h>
#include <rime/algo/syllabifier.h>
#include <rime/dict/corrector.h>
#include <rime/dict/dictionary.h>
#include <rime/dict/user_dictionary.h>
#include <rime/gear/grammar.h>
#include <rime/gear/poet.h>
#include <rime/gear/script_translator.h>
#include <rime/gear/translator_commons.h>

// static const char* quote_left = "\xef\xbc\x88";
// static const char* quote_right = "\xef\xbc\x89";

namespace rime {

// WhaleIME: 与 syllabifier.cc 里的 CORR_STATS 同一开关，分段打一行耗时
// （组音节图 / 词典 Lookup / 组句），默认关闭。
static bool CorrStatsEnabled() {
  static const bool enabled = ::getenv("CORR_STATS") != nullptr;
  return enabled;
}
static double CorrNowMs() {
  return std::chrono::duration<double, std::milli>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}

namespace {

struct SyllabifyTask {
  const Code& code;
  const SyllableGraph& graph;
  size_t target_pos;
  function<void(SyllabifyTask* task,
                size_t depth,
                size_t current_pos,
                size_t next_pos)>
      push;
  function<void(SyllabifyTask* task, size_t depth)> pop;
};

static bool syllabify_dfs(SyllabifyTask* task,
                          size_t depth,
                          size_t current_pos) {
  if (depth == task->code.size()) {
    return current_pos == task->target_pos;
  }
  SyllableId syllable_id = task->code.at(depth);
  auto z = task->graph.edges.find(current_pos);
  if (z == task->graph.edges.end())
    return false;
  // favor longer spellings
  for (const auto& y : boost::adaptors::reverse(z->second)) {
    size_t end_vertex_pos = y.first;
    if (end_vertex_pos > task->target_pos)
      continue;
    auto x = y.second.find(syllable_id);
    if (x != y.second.end()) {
      task->push(task, depth, current_pos, end_vertex_pos);
      if (syllabify_dfs(task, depth + 1, end_vertex_pos))
        return true;
      task->pop(task, depth);
    }
  }
  return false;
}

// WhaleIME: 候选排序键（越大越靠前）。
//
// 上游的候选流是「按覆盖到的输入位置分桶、从最长的桶开始逐个吐出」，桶内才比较
// 词频（entry->weight 里已经含纠错惩罚 credibility）。这个结构对精确拼写成立：
// 覆盖得越多，解释得越完整。但纠错候选破坏了这条前提——一个四个音节全改过的词
// （zhebuhaoba → 呵护宝宝）、或者改掉半截输入的长词，同样落进「覆盖最长」的桶，
// 于是无条件排到所有精确候选之前，桶内 0.01^编辑距离 的惩罚根本没机会生效。
//
// 这里把排序的主键从「覆盖长度」换成「可信覆盖」（cover）：
//   cover = 解释到的字节数 − 误按键数
//   1) cover 大的在前；
//   2) 再看覆盖位置（cover 相同时，解释得更远的更好）；
//   3) 最后看概率（词频权重 / 整句得分）。
// 于是 "zhonguo" 里 中国（zhong 精确 + uo 纠错，1 键）仍然压过组句「中或」——两者
// cover 相同，而整词的对数权重远高于两个单字之和；而 "zhebuhaoba" 里四个音节
// 全错的 呵护宝宝（4 键）是 11 − 4 = 7，仍掉在 这不好（8）之后。
//
// 扣「误按键数」而不是「被改写的字节数」（2026-11-08 修 zhiyiu 出不来 只有）：
// 一次误按改写的字节数取决于该音节的拼写长度 —— yiu → you 只错一个键，却把整个
// 3 字节音节记成纠错。旧的 cover = 精确字节 + 纠错字节 × 0.5 因此让
// 「zhi + you（错 1 键，覆盖满 6 字节）」只拿到 3 + 1.5 = 4.5，输给
// 「zhi + yi（0 键，剩一个 u 没解释）」的 5.0；而 zhi'yi 在词典里有 41 条同码词，
// 于是 只有 被挤到第 41 位（页内根本看不到）。按误按次数扣分后两者同为 5.0，
// 再由覆盖位置裁决 —— 解释到输入末尾的 只有 在前。
// 同一条路径的覆盖终点是固定的（都走到 target），所以「cover 最大」等价于
// 「误按键数最少」，路径选择也按这个口径做（见 probe_path）。
//
// 曾试过在 score 之前加「词数少的优先」，用来表达「同一区间整词优于组句」。
// 全拼下没问题，但九键（T9）下这个键会变成第一主键并压过概率：一串按键可以切
// 成完全不同的音节（943 28 426 → zhe'bu'hao 或 yi'da'tiao），两边 cover 都是满的，
// 「一大跳」靠词数 1 < 3 无条件赢过「这不/这不好」。词数只反映结构，不反映这句
// 话有多常见 —— 概率才是可比的量，所以这里只比 score。

// WhaleIME 2026-11-07：同一个 end_pos 桶内，「原样敲的键」必须先于「改过键的」出
// （修 beng→本个/本/能? 压在 崩 前面、xi→非 xi 字、jifang→你放? 压在 己方 前面）
//
// cover 只能表达「解释了多少输入」，表达不了「有没有改动用户敲的键」。而单音节输入
// （beng / xi）下精确候选与纠错候选全部落在**同一个 end_pos 桶**里，桶内次序由词典
// 给出（词条权重 + 一次 -log(0.01)≈-4.6 的纠错惩罚）—— cover 只在桶与桶之间生效，
// 桶内根本参与不到比较。于是「ben / geng / neng 改一个键」靠词频（本 10.4 / 更 11.7 /
// 能 12.4，均为 ln 词频）就能压过「beng 原样」（崩 7.7）。多音节同理：jifang 里
// 你放（ji→ni 改键、覆盖 6 字节）与 己方（ji|fang 全精确、覆盖 6 字节）同桶，
// 谁词频/用户词权重高谁在前。
//
// 修法不是改主键（试过把「忠实度」当第一主键，会让「简拼凑满整串」的垃圾长词
// ——zhonguo → 做你该做的事——靠满覆盖冲到第一），而是**只调整桶内次序**：
// 整段输入能被精确音节切完时（exact_full_coverage_），桶里「原样解释」的词条先出、
// 改键的纠错词条扣到 deferred 里、等本桶精确词条吐完再放出来（见 TakeExactEntry）。
// 跨桶比较仍然只看 cover/end_pos/score，行为与上游/既有实测一致。
//
// 2026-11-09 补充（shyzi → 数字）：上面这条「原样优先」只对**合法拼音输入**成立。
// 用户敲的是缩写串（sh|y|zi）时，「原样」条目本身就是简拼猜测（啥样子 / 树叶子 /
// 十一子），没资格无条件压过「编码长度正好等于输入长度、只错一个键」的真词
// （数字 shu'zi，词频 10.05 对 2.20）。所以简拼串（abbrev_input_）里放开两类东西：
// TakeExactEntry 让 covers_input 的改键条目按词频直接出，InitSlots 把这类桶的
// 跨桶 cover 固定成它覆盖到的位置，避免桶头一跳就让整桶沉下去。
//
// 2026-11-14 补充（xianf / xianh → 想）：原样优先的另一个前提是「这个桶里的原样条目
// 真的是把用户敲的键解释掉了」。尾部悬空（hanging_tail_：完整拼写只覆盖到 consumed
// 之前，末键只能当某个音节的缩写）时并不是 —— xianf 的五十多条「原样」条目
// （先发 xian'fa / 宪法 / 掀翻 / 先富…）全是靠缩写把末键 f 补全出来的猜测，它和
// 「f 打成 g、整串读作 xiang」的纠错是同一件未知事。把纠错扣到它们后面，等于让
// 「缩写猜测」无条件赢过「误按猜测」，想（词频是 先锋 的 40 倍）因此掉到第 53 位。
// 这类输入的满覆盖桶不再扣纠错（见 TakeExactEntry），两边改由词典次序（词频 +
// 类型先验）竞争；全拼输入不受影响，本/能 压 崩 那个 badcase（C1）仍由原样优先兜住。

struct RankKey {
  double cover = 0.;   // 可信覆盖：解释到的字节数 − 误按键数（见上）
  size_t end_pos = 0;  // 覆盖到的位置（相对段首）
  size_t parts = 1;    // 词数（仅用于诊断，不参与比较）
  double score = 0.;   // 词频权重 / 整句得分，越大越常用
};

inline bool IsBetterKey(const RankKey& a, const RankKey& b) {
  if (a.cover != b.cover)
    return a.cover > b.cover;
  if (a.end_pos != b.end_pos)
    return a.end_pos > b.end_pos;
  return a.score > b.score;
}

// 在音节图上匹配 code 的一条完整路径的代价：哪些字节是「原样敲的键」、改了几个键。
// 纠错边（is_correction）的原样字节记 0；补全边算原样（用户确实敲了那部分键）。
// 路径按「误按最少、其次原样字节最多」挑一条 —— 同一条路径覆盖的终点固定，
// 所以这就是 cover 最大的那条。
struct PathScore {
  bool ok = false;
  size_t exact = 0;
  size_t edits = 0;  // 误按键数（纠错边的编辑距离之和）
};

PathScore probe_path(const SyllableGraph& graph,
                     const Code& code,
                     size_t depth,
                     size_t pos,
                     size_t target,
                     vector<PathScore>* memo,
                     vector<char>* known) {
  if (depth == code.size())
    return pos == target ? PathScore{true, 0} : PathScore{};
  const size_t slot = depth * (target + 1) + pos;
  if ((*known)[slot])
    return (*memo)[slot];
  PathScore best;
  auto z = graph.edges.find(pos);
  if (z != graph.edges.end()) {
    const SyllableId syllable_id = code.at(depth);
    for (const auto& y : z->second) {
      const size_t end_pos = y.first;
      if (end_pos > target)
        continue;
      auto x = y.second.find(syllable_id);
      if (x == y.second.end())
        continue;
      const PathScore sub =
          probe_path(graph, code, depth + 1, end_pos, target, memo, known);
      if (!sub.ok)
        continue;
      const bool corrected = x->second.is_correction;
      const size_t exact = sub.exact + (corrected ? 0 : end_pos - pos);
      // 纠错边少了 correction_distance 时（非本引擎生成、只有 is_correction 标记的
      // spelling）至少算错一个键，不能让它白拿满覆盖。
      const size_t edits =
          sub.edits + (corrected
                           ? (std::max)((size_t)1, x->second.correction_distance)
                           : 0);
      if (!best.ok || edits < best.edits ||
          (edits == best.edits && exact > best.exact)) {
        best.ok = true;
        best.exact = exact;
        best.edits = edits;
      }
    }
  }
  (*memo)[slot] = best;
  (*known)[slot] = 1;
  return best;
}

// ---------------------------------------------------------------------------
// WhaleIME: 句首 n-gram 打分（2026-10-26）
//
// 动机：librime-octagram 的 Octagram::Query 在 context 为空时**直接返回常数**
// non_collocation_penalty（octagram.cc 开头 `if (!db_ || context.empty())`），而
// ContextualTranslation 传进来的 context 是「已上屏的前文」——句首输入时为空。
// 结果：语言模型给全部候选加同一个常数，候选顺序完全退化成词条权重。九键的
// 满覆盖多切分输入（94328426 → zhe'bu'hao 或 yi'da'tiao）就此失去唯一的裁决依据，
// 只能比 一大跳(13930) 与 这不好/写不好(100 占位) 这种不可比的词条权重。
//
// 为什么不去改 octagram.cc：不为 librime 升级增加维护面。这里改用 Query 自己的
// 组合语义绕过——Query(context, word) 查的键就是 context+word 拼接起来的那串字，
// 所以把词拆成「首字 + 其余」再查，命中的键与整词键完全相同，不必让上游把
// context.empty() 的分支改掉。
//
// 语义说明（避免过度承诺）：这里的分数是「该词在 n-gram 模型里的关联强度」，
// 不是严格的 P(<s> 词)。词若本身是 ARPA 的一元词条（如 一大跳），键就是
// `<s>词` 这个句首二元组；否则键来自 `这 不好` / `这 不 好` 这类连续 n-gram。
// 两者都是「这串字一起出现得有多频繁」，足以裁决切分之间的取舍。
//
// 返回值与 entry->weight 同量纲：gram db 存的是 int(ln(count) * 10000)，
// Query 还原成 ln(count) 再加 collocation_penalty。库里没有该键时返回
// non_collocation_penalty，天然排在所有有证据的候选之后。

size_t Utf8CharBytes(const string& text, size_t at) {
  const unsigned char c = static_cast<unsigned char>(text[at]);
  if (c < 0x80)
    return 1;
  if ((c & 0xE0) == 0xC0)
    return 2;
  if ((c & 0xF0) == 0xE0)
    return 3;
  if ((c & 0xF8) == 0xF0)
    return 4;
  return 1;
}

// 上游 Query 的查询窗口是「context 3 字 + word 3 字」，超过 6 字的词装不下，
// 此时返回 false 让调用方退回词条权重。
bool SentenceStartGramScore(Grammar* grammar,
                            const string& word,
                            double* score) {
  if (!grammar || word.empty())
    return false;
  vector<size_t> char_at(1, 0);  // 每个 UTF-8 字符的起始字节
  for (size_t i = 0; i < word.size();) {
    i += Utf8CharBytes(word, i);
    char_at.push_back(i);
  }
  const size_t chars = char_at.size() - 1;
  if (chars < 2 || chars > 6)
    return false;
  // context 取前 (chars - 3) 个字符、至少 1 个；其余留给 word（至多 3 个）
  const size_t context_chars = chars > 4 ? chars - 3 : 1;
  const string context = word.substr(0, char_at[context_chars]);
  const string tail = word.substr(char_at[context_chars]);
  *score = grammar->Query(context, tail, false);
  return true;
}

// 「库里没有这条 n-gram 证据」时用的分数，取值与 octagram 默认的
// non_collocation_penalty 一致（Query 查不到键时返回的就是它）。
const double kNoGramEvidence = -12.0;

// rime-ice 的 ext / tencent 两张表整表权重都是 100，表示「没有词频数据」，
// table.bin 里存成 ln(100)。这个值越等于「不可信」，不是「很低」。
const double kPlaceholderWeight = 4.605170185988091;  // ln(100)

// code 在音节图上匹配到 target 时，每个音节结束的输入位置（相对段首）。
// 用来给候选标注「属于哪一种切分」——见 RerankFullCoverageSlot。
bool CollectSyllableEnds(const SyllableGraph& graph,
                         const Code& code,
                         size_t target,
                         vector<size_t>* ends) {
  ends->clear();
  SyllabifyTask task{
      code, graph, target,
      [&](SyllabifyTask*, size_t, size_t, size_t next_pos) {
        ends->push_back(next_pos);
      },
      [&](SyllabifyTask*, size_t) { ends->pop_back(); }};
  return syllabify_dfs(&task, 0, 0);
}

// 只走精确边（is_correction == false）的满覆盖路径条数，封顶 cap。
// 非纠错边的路径才代表「输入本身的读法」：纠错带来的额外路径不代表切分歧义，
// 否则 26 键会被误判成多切分。
size_t ExactPathCount(const SyllableGraph& graph, size_t target, size_t cap) {
  if (target < 1 || cap == 0)
    return 0;
  vector<size_t> ways(target + 1, 0);
  ways[0] = 1;
  for (size_t pos = 0; pos < target; ++pos) {
    if (!ways[pos])
      continue;
    auto it = graph.edges.find(pos);
    if (it == graph.edges.end())
      continue;
    for (const auto& seg : it->second) {
      const size_t end = seg.first;
      if (end <= pos || end > target)
        continue;
      bool exact = false;
      for (const auto& edge : seg.second) {
        if (!edge.second.is_correction) {
          exact = true;
          break;
        }
      }
      if (!exact)
        continue;
      ways[end] = (std::min)(cap, ways[end] + ways[pos]);
    }
  }
  return ways[target];
}

// 「输入存在 ≥2 种满覆盖音节切分」的判据（九键切分先验用）
bool HasMultipleExactPaths(const SyllableGraph& graph, size_t target) {
  return target >= 2 && ExactPathCount(graph, target, 2) > 1;
}

// WhaleIME 2026-11-14: 整段输入是不是「一个音节」（ke / beng / xi）—— 起点就有一条
// 非纠错边跨到末尾。这类输入的满覆盖桶里没有词，全是这个音节的同音字表，桶内
// 「原样优先」等于把纠错候选隔离到整张同音字表之后（ke 的 了 排到 50 名外），
// 所以那个桶改用有限偏置归并（见 TakeExactEntry）。
bool HasSingleSyllableCoverage(const SyllableGraph& graph, size_t target) {
  if (target < 1)
    return false;
  auto it = graph.edges.find(0);
  if (it == graph.edges.end())
    return false;
  for (const auto& seg : it->second) {
    if (seg.first != target)
      continue;
    for (const auto& edge : seg.second) {
      if (!edge.second.is_correction)
        return true;
    }
  }
  return false;
}

// WhaleIME: 整段输入能否被「精确音节」切完 —— 「桶内原样优先」（exact_full_coverage_）的前提。
// 补全边算精确：用户确实敲了那几个键，只是词还没打完。
bool HasExactFullCoverage(const SyllableGraph& graph, size_t target) {
  return ExactPathCount(graph, target, 1) > 0;
}

// WhaleIME: 整段输入能否被「完整拼写」的音节切完（简拼不算）。
//
// 与 HasExactFullCoverage 的区别只在简拼：lsi 能靠 l（l 的简拼）+ si 精确切完，
// 但没有任何一个音节是全拼，说明用户敲的键本身不是一句合法拼音。这种输入里
// 「简拼 + 补全」猜出来的长词（类似/罗斯/螺丝…）会成片排在前面，把真正的单键
// 纠错（lsi → lai → 来）压到几十名开外，用户翻不到。所以只有这个判据为 false
// 时才启用「纠错条目提前」（见 HoistCorrections）。
//
// 拼写长度靠 Table 反查：l 边对应音节 lei，拼写 "lei" 长 3 ≠ 边覆盖的 1 字节，
// 就能认出简拼。algebra 的 abbrev 产物在 prism 里仍是 normal spelling，
// SpellingProperties.type 区分不了（实测 type 全是 kNormalSpelling）。
size_t FullSpellingPathCount(const SyllableGraph& graph,
                             size_t target,
                             size_t cap,
                             Table* table) {
  if (target < 1 || cap == 0 || !table)
    return 0;
  vector<size_t> ways(target + 1, 0);
  ways[0] = 1;
  for (size_t pos = 0; pos < target; ++pos) {
    if (!ways[pos])
      continue;
    auto it = graph.edges.find(pos);
    if (it == graph.edges.end())
      continue;
    for (const auto& seg : it->second) {
      const size_t end = seg.first;
      if (end <= pos || end > target)
        continue;
      bool full_spelling = false;
      for (const auto& edge : seg.second) {
        if (edge.second.is_correction)
          continue;
        if (table->GetSyllableById(edge.first).size() == end - pos) {
          full_spelling = true;
          break;
        }
      }
      if (!full_spelling)
        continue;
      ways[end] = (std::min)(cap, ways[end] + ways[pos]);
    }
  }
  return ways[target];
}

bool HasFullSpellingCoverage(const SyllableGraph& graph,
                             size_t target,
                             Table* table) {
  return FullSpellingPathCount(graph, target, 1, table) > 0;
}

// [0, target] 里哪些位置能被「非纠错边」精确覆盖到 —— 一次 DP 算出所有分桶的
// 「这个桶里还有原样条目」判据（见 RankKey 的 fixed_cover / InitSlots）。
void MarkExactReachable(const SyllableGraph& graph,
                        size_t target,
                        vector<char>* reachable) {
  reachable->assign(target + 1, 0);
  if (target < 1)
    return;
  (*reachable)[0] = 1;
  for (size_t pos = 0; pos < target; ++pos) {
    if (!(*reachable)[pos])
      continue;
    auto it = graph.edges.find(pos);
    if (it == graph.edges.end())
      continue;
    for (const auto& seg : it->second) {
      const size_t end = seg.first;
      if (end <= pos || end > target)
        continue;
      for (const auto& edge : seg.second) {
        if (!edge.second.is_correction) {
          (*reachable)[end] = 1;
          break;
        }
      }
    }
  }
}

// WhaleIME 2026-11-09: 只走「完整拼写」的非纠错边能覆盖到的最远输入位置 ——
// 也就是「用户到底把多少个字节的音节完整敲出来了」。
//
// 用途是区分两类「拼不出完整拼写」的输入：
//   shyzi         → 最远只到 4（只有 zi 是全拼，sh/y 都是简拼），缩写串；
//   dapiyinf      → 最远到 7（da'pi'yin 全拼，末键 f 靠简拼兜住），末尾误按。
// 前者整串都不是拼音，桶内「原样优先」没有意义（见 abbrev_input_）；后者本质是
// 合法拼音 + 一个错键，仍要走原样优先，否则误按语料的召回会掉（见 C5）。
size_t MaxFullSpellingEnd(const SyllableGraph& graph,
                          size_t target,
                          Table* table) {
  if (target < 1 || !table)
    return 0;
  vector<char> reach(target + 1, 0);
  reach[0] = 1;
  size_t farthest = 0;
  for (size_t pos = 0; pos <= target; ++pos) {
    if (!reach[pos])
      continue;
    if (pos > farthest)
      farthest = pos;
    auto it = graph.edges.find(pos);
    if (it == graph.edges.end())
      continue;
    for (const auto& seg : it->second) {
      const size_t end = seg.first;
      if (end <= pos || end > target)
        continue;
      for (const auto& edge : seg.second) {
        if (edge.second.is_correction)
          continue;
        if (table->GetSyllableById(edge.first).size() == end - pos) {
          reach[end] = 1;
          break;
        }
      }
    }
  }
  return farthest;
}

}  // anonymous namespace

class ScriptSyllabifier : public PhraseSyllabifier {
 public:
  ScriptSyllabifier(ScriptTranslator* translator,
                    Corrector* corrector,
                    const string& input,
                    size_t start)
      : translator_(translator),
        input_(input),
        start_(start),
        syllabifier_(translator->delimiters(),
                     translator->enable_completion(),
                     translator->strict_spelling()) {
    if (corrector) {
      syllabifier_.EnableCorrection(corrector);
    }
  }

  virtual Spans Syllabify(const Phrase* phrase);
  size_t BuildSyllableGraph(Prism& prism);
  string GetPreeditString(const Phrase& cand) const;
  string GetOriginalSpelling(const Phrase& cand) const;
  bool IsCorrection(const Code& code, size_t code_length) const;
  // WhaleIME: code 在 [code_start, code_end) 上「原样解释」的字节数。
  // found 为 false 表示走不到终点（预测词：编码比已输入的串长），此时按整段算
  // 原样，避免把补全候选误判成纠错而沉底。
  // edits（可空）返回这条路径的误按键数，供候选排序按「改了几个键」扣分。
  size_t MeasureExactBytes(const Code& code,
                           size_t code_start,
                           size_t code_end,
                           bool* found,
                           size_t* edits = nullptr) const;

  const SyllableGraph& syllable_graph() const { return syllable_graph_; }
  // WhaleIME: 音节图里是否存在纠错边。为 false 时（九键、双拼、没开纠错的方案）
  // 「桶内原样优先」与「跳过改键条目」全部空转，热路径上不多做一次 DP。
  bool has_corrections() const { return has_corrections_; }

 protected:
  // WhaleIME: [begin_pos, end_pos) 这段输入「按用户敲的键」的精确音节切分。
  // 走不到终点（这段输入本身切不完整、或只能靠纠错边切）时返回 false。
  bool FaithfulSplit(size_t pos,
                     size_t end_pos,
                     vector<string>* parts) const;

  ScriptTranslator* translator_;
  string input_;
  size_t start_;
  Syllabifier syllabifier_;
  SyllableGraph syllable_graph_;
  bool has_corrections_ = false;
};

class ScriptTranslation : public Translation {
 public:
  ScriptTranslation(ScriptTranslator* translator,
                    Corrector* corrector,
                    Poet* poet,
                    const an<Grammar>& grammar,
                    const string& input,
                    size_t start,
                    size_t end_of_input,
                    int max_sentences,
                    double sentence_cutoff_threshold,
                    bool enable_segmentation_prior)
      : translator_(translator),
        poet_(poet),
        grammar_(grammar),
        start_(start),
        end_of_input_(end_of_input),
        enable_segmentation_prior_(enable_segmentation_prior),
        syllabifier_(
            New<ScriptSyllabifier>(translator, corrector, input, start)),
        enable_correction_(corrector),
        max_sentences_(max_sentences),
        sentence_cutoff_threshold_(sentence_cutoff_threshold) {
    set_exhausted(true);
  }
  bool Evaluate(Dictionary* dict, UserDictionary* user_dict);
  virtual bool Next();
  virtual an<Candidate> Peek();

 protected:
  // 一个分桶（同一覆盖位置）的游标：系统词库 / 用户词库各占一个槽。
  // 槽内顺序仍由词典给出的词频顺序决定，跨槽顺序由 RankKey 决定。
  struct Slot {
    size_t end_pos = 0;
    DictEntryIterator* sys = nullptr;
    UserDictEntryIterator* usr = nullptr;
    an<DictEntry> head;
    RankKey key;
    bool head_valid = false;
    // 当前桶头是否「原样解释」了输入（没改动用户敲的键），见 TakeExactEntry。
    bool faithful = false;
    // WhaleIME: 句首 + 多切分满覆盖桶的「顶部重排」缓冲。
    // 桶内顺序本来由词典给出（按词条权重），RankKey 只能决定桶与桶之间、以及
    // 桶头的先后，改不动桶内次序。这里把桶的前 kRerankLimit 条先取出来、按
    // n-gram 分重排后逐条吐出，之后继续走原迭代器——尾部保持词典原序、不丢候选。
    vector<an<DictEntry>> reranked;
    size_t reranked_pos = 0;
    bool rerank_done = false;
    // WhaleIME: 本桶里被跳过、暂存的纠错条目（见 TakeExactEntry）。
    // 一个 end_pos 桶同时装着「原样匹配」和「改过键」的词条，而桶内次序由词典按
    // 权重给出，纠错词条会靠词频挤到精确词条前面。这里先把它们扣下，等本桶的精确
    // 词条吐完再放出来 —— 上限与全局纠错限流一致，多出来的本来也会被丢掉。
    vector<an<DictEntry>> deferred;
    size_t deferred_pos = 0;
    // WhaleIME 2026-11-14: 被暂存纠错条目插了队的原样条目（见 TakeExactEntry 的裕度
    // 归并）。它已经从底层流里取出来了，必须在这里挂一轮，否则会连同这次取值一起丢掉。
    an<DictEntry> pending;
    // WhaleIME: 已提到桶头的改键条目（见 HoistCorrections）。这些条目要单独标
    // type=correction，否则会被 ContextualTranslation 的同 end_pos 重排洗回去。
    vector<an<DictEntry>> hoisted;
    // WhaleIME 2026-11-09: 桶的跨桶优先级（cover）固定为 end_pos，不再随桶头
    // 是原样条目还是改键条目而跳动。只在简拼串上设置，见 InitSlots。
    double fixed_cover = 0.;
  };

  bool CheckEmpty();
  bool IsNormalSpelling() const;
  bool PrepareCandidate();
  void InitSlots();
  bool SlotHasEntry(const Slot& slot) const;
  an<DictEntry> TakeRawEntry(Slot* slot);
  an<DictEntry> TakeExactEntry(Slot* slot);
  bool EndsWithCorrection(const Phrase& cand) const;
  // WhaleIME: 一段编码在 [code_start, code_end) 上的可信覆盖 = 解释到的字节数 −
  // 误按键数（见文件头的 RankKey 说明）。预测词（编码比输入长）按整段原样计。
  double Coverage(const Code& code, size_t code_start, size_t code_end) const;
  // WhaleIME: 编码的完整拼写长度（各音节拼写字母数之和）。与「覆盖到的按键数」
  // 相等时说明这条词条没有靠缩写扩写，见 TakeExactEntry。table_ 为空时返回 0。
  size_t CodeSpellingLength(const Code& code) const;
  bool LoadSlotHead(Slot* slot);
  void AdvanceSlot(Slot* slot);
  void RerankFullCoverageSlot(Slot* slot);
  // WhaleIME: 简拼串满覆盖桶按「词条权重 − 拼写长度偏差」重排，见实现处的说明。
  void RerankAbbrevSlot(Slot* slot);
  void HoistCorrections(Slot* slot);
  RankKey KeyOfSentence(const Sentence& sentence) const;
  template <class QueryResult>
  void EnrollEntries(map<int, DictEntryList>& entries_by_end_pos,
                     const an<QueryResult>& query_result);
  WordGraph PrepareForMakingSentence(Dictionary* dict,
                                     UserDictionary* user_dict);
  an<Sentence> MakeSentence(Dictionary* dict, UserDictionary* user_dict);
  deque<an<Sentence>> MakeSentences(Dictionary* dict,
                                    UserDictionary* user_dict);

  ScriptTranslator* translator_;
  Poet* poet_;
  an<Grammar> grammar_;
  size_t start_;
  size_t end_of_input_;
  an<ScriptSyllabifier> syllabifier_;

  // WhaleIME 句首 n-gram 打分（见文件里 SentenceStartGramScore 的说明）。
  // consumed_：本次输入被音节图解释到的位置；use_sentence_start_gram_：
  // 「句首 + 满覆盖 + 输入存在多种满覆盖切分」三条同时成立时才切换打分来源。
  size_t consumed_ = 0;
  bool use_sentence_start_gram_ = false;
  bool enable_segmentation_prior_ = false;

  // WhaleIME: 整段输入能否被精确音节切完（有纠错边且存在满覆盖精确路径）。
  // 只有它为 true 时，桶里的改键条目才让位给原样条目 —— 用户敲的是
  // 合法拼音，第一候选必须是「拼音本身对应的字/词」。见文件头与 TakeExactEntry 的说明。
  bool exact_full_coverage_ = false;
  // WhaleIME 2026-11-09: 「简拼串」—— 整段输入能靠简拼/缩写边切完（shyzi =
  // sh|y|zi），但拼不出完整拼写。这种输入里「原样」条目（啥样子/树叶子/十一子）
  // 只是缩写猜测，不该无条件压过「编码长度正好等于输入长度、只错一个键」的真词
  // （数字 shu'zi）。见 TakeExactEntry 与 InitSlots 的 fixed_cover。
  bool abbrev_input_ = false;
  // WhaleIME 2026-11-14: 「尾部悬空」—— 整段输入能被精确边切完，但拼不出完整拼写
  // （xianf = xian + f，末键 f 只是某个 f 开头音节的缩写）。这时桶里的「原样」条目
  // 全是靠缩写把末键补全出来的猜测（先发/宪法/掀翻…），它们对末键的判断与「把 f
  // 当成 g」的纠错同类，没有资格整批排在纠错之前。见 TakeExactEntry。
  bool hanging_tail_ = false;
  // WhaleIME 2026-11-14: 整段输入就是一个音节（ke / beng / xi）。这类输入的满覆盖桶
  // 全是同音字，原样优先要退化成「有限偏置归并」，见 TakeExactEntry。
  bool single_syllable_ = false;
  // WhaleIME: 输入拼不出完整拼写（lsi 这类敲错键的串）时为 true，用于触发
  // 「满覆盖改键条目提前」（见 HoistCorrections）。与 exact_full_coverage_ 分开：
  // 后者管的是「原样优先」，口径和用途都不同。
  bool hoist_corrections_ = false;
  Table* table_ = nullptr;

  an<DictEntryCollector> phrase_;
  an<UserDictEntryCollector> user_phrase_;
  deque<an<Sentence>> sentences_;

  vector<Slot> slots_;
  int current_slot_ = -1;
  RankKey sentence_key_;
  bool sentence_key_valid_ = false;

  an<Phrase> candidate_ = nullptr;
  size_t candidate_index_ = 0;
  enum CandidateSource {
    kUninitialized,
    kUserPhrase,
    kSysPhrase,
    kSentence,
  };
  CandidateSource candidate_source_ = kUninitialized;

  // 纠错候选的全局上限（见 Next() 的限流）。短输入把改键条目提到桶头时放宽到 8：
  // 这类输入（bsi / lsi）每个「误按方向」都是一个音节组（bai / bei / bo / bi…），
  // 只留 4 条就只剩前一两个组，另一组（白/百）根本露不出来。
  size_t correction_limit() const { return hoist_corrections_ ? 8 : 4; }
  size_t correction_count_ = 0;
  int max_sentences_ = 1;
  double sentence_cutoff_threshold_ = 0.1;

  bool enable_correction_;
};

// ScriptTranslator implementation

ScriptTranslator::ScriptTranslator(const Ticket& ticket)
    : Translator(ticket), Memory(ticket), TranslatorOptions(ticket) {
  if (!engine_)
    return;
  if (Config* config = engine_->schema()->config()) {
    config->GetInt(name_space_ + "/spelling_hints", &spelling_hints_);
    config->GetInt(name_space_ + "/max_word_length", &max_word_length_);
    config->GetInt(name_space_ + "/core_word_length", &core_word_length_);
    config->GetBool(name_space_ + "/always_show_comments",
                    &always_show_comments_);
    config->GetBool(name_space_ + "/enable_correction", &enable_correction_);
    if (!config->GetBool(name_space_ + "/enable_word_completion",
                         &enable_word_completion_)) {
      enable_word_completion_ = enable_completion_;
    }
    config->GetInt(name_space_ + "/max_homophones", &max_homophones_);
    poet_.reset(new Poet(language(), config));
  }
  if (enable_correction_) {
    if (auto* corrector = Corrector::Require("corrector")) {
      corrector_.reset(corrector->Create(ticket));
    }
  }
}

an<Translation> ScriptTranslator::Query(const string& input,
                                        const Segment& segment) {
  if (!dict_ || !dict_->loaded())
    return nullptr;
  if (!segment.HasAnyTagIn(tags_))
    return nullptr;
  DLOG(INFO) << "input = '" << input << "', [" << segment.start << ", "
             << segment.end << ")";

  FinishSession();

  bool enable_user_dict =
      user_dict_ && user_dict_->loaded() && !IsUserDictDisabledFor(input);

  size_t end_of_input = engine_->context()->input().length();
  an<Grammar> grammar;
  bool segmentation_prior = false;
  if (Config* schema_config = engine_->schema()->config()) {
    // 默认关闭：这个重排只在「按键是多对一映射、同串能切出不同音节」的方案里
    // 才划算（九键）。26 键实测开启后 top1 从 77.5% 掉到 30.0%——那里词条权重
    // 本来就是对的，用 n-gram 分覆盖掉它是纯粹的损失。
    schema_config->GetBool(name_space_ + "/segmentation_prior",
                           &segmentation_prior);
    // 只有真正要重排时才建 Grammar 句柄（Poet 内部的语法对象不对外暴露）。
    // 插件组件按语言缓存 .gram（OctagramComponent::GetDb），所以这里只是多一个
    // 薄壳，不会重复 mmap 那个 68MB 的模型。
    string grammar_language;
    if (segmentation_prior &&
        schema_config->GetString("grammar/language", &grammar_language)) {
      if (auto* component = Grammar::Require("grammar"))
        grammar.reset(component->Create(schema_config));
    }
  }
  // the translator should survive translations it creates
  auto result = New<ScriptTranslation>(
      this, corrector_.get(), poet_.get(), grammar, input, segment.start,
      end_of_input, max_sentences_, sentence_cutoff_threshold_,
      segmentation_prior);
  if (!result || !result->Evaluate(
                     dict_.get(), enable_user_dict ? user_dict_.get() : NULL)) {
    return nullptr;
  }
  auto deduped = New<DistinctTranslation>(result);
  if (contextual_suggestions_) {
    return poet_->ContextualWeighted(deduped, input, segment.start, this);
  }
  return deduped;
}

int ScriptTranslator::core_word_length() const {
  if (max_word_length_ <= 0) {
    return core_word_length_;
  }
  return (std::min)(core_word_length_, max_word_length_);
}

static bool exceed_upperlimit(int length, int upper_limit) {
  return upper_limit > 0 && length > upper_limit;
}

bool ScriptTranslator::SaveCommitEntry(CommitEntry& commit_entry) {
  if (exceed_upperlimit(commit_entry.Length(), max_word_length())) {
    UpdateElements(commit_entry);
  } else {
    commit_entry.Save();
  }
  return true;
}

bool ScriptTranslator::ConcatenatePhrases(CommitEntry& commit_entry,
                                          const vector<an<Phrase>>& phrases) {
  const int kCoreWordLength = core_word_length();
  const int n = phrases.size();
  for (int i = 0; i < n; ++i) {
    int cur_len = 0;
    int j = i;
    for (; j < n; ++j) {
      commit_entry.AppendPhrase(phrases.at(j));
      cur_len += phrases.at(j)->code().size();
      if (kCoreWordLength <= 0 || cur_len > kCoreWordLength) {
        break;
      }
      SaveCommitEntry(commit_entry);
    }
    if (kCoreWordLength > 0) {
      if (j == i) {
        SaveCommitEntry(commit_entry);
      }
      commit_entry.Clear();
    }
  }
  SaveCommitEntry(commit_entry);
  commit_entry.Clear();

  return true;
}

bool ScriptTranslator::ProcessSegmentOnCommit(CommitEntry& commit_entry,
                                              const Segment& seg) {
  auto phrase =
      As<Phrase>(Candidate::GetGenuineCandidate(seg.GetSelectedCandidate()));
  bool recognized = Language::intelligible(phrase, this);
  if (recognized) {
    queue_.push_back(phrase);
  }

  if (!recognized || seg.status >= Segment::kConfirmed) {
    ConcatenatePhrases(commit_entry, queue_);
    queue_.clear();
  }

  return true;
}

string ScriptTranslator::FormatPreedit(const string& preedit) {
  string result = preedit;
  preedit_formatter_.Apply(&result);
  return result;
}

string ScriptTranslator::Spell(const Code& code) {
  string result;
  vector<string> syllables;
  if (!dict_ || !dict_->Decode(code, &syllables) || syllables.empty())
    return result;
  result = boost::algorithm::join(syllables, string(1, delimiters_.at(0)));
  comment_formatter_.Apply(&result);
  return result;
}

string ScriptTranslator::GetPrecedingText(size_t start) const {
  return !contextual_suggestions_ ? string()
         : start > 0 ? engine_->context()->composition().GetTextBefore(start)
                     : engine_->context()->commit_history().latest_text();
}

bool ScriptTranslator::UpdateElements(const CommitEntry& commit_entry) {
  bool update_elements = false;
  // avoid updating single character entries within a phrase which is
  // composed with single characters only
  if (commit_entry.elements.size() > 1) {
    for (const DictEntry* e : commit_entry.elements) {
      if (e->code.size() > 1) {
        update_elements = true;
        break;
      }
    }
  }
  if (update_elements) {
    for (const DictEntry* e : commit_entry.elements) {
      user_dict_->UpdateEntry(*e, 0);
    }
  }
  return true;
}

bool ScriptTranslator::Memorize(const CommitEntry& commit_entry) {
  UpdateElements(commit_entry);
  user_dict_->UpdateEntry(commit_entry, 1);
  return true;
}

// ScriptSyllabifier implementation

Spans ScriptSyllabifier::Syllabify(const Phrase* phrase) {
  Spans result;
  vector<size_t> vertices;
  vertices.push_back(start_);
  SyllabifyTask task{
      phrase->code(), syllable_graph_, phrase->end() - start_,
      [&](SyllabifyTask* task, size_t depth, size_t current_pos,
          size_t next_pos) { vertices.push_back(start_ + next_pos); },
      [&](SyllabifyTask* task, size_t depth) { vertices.pop_back(); }};
  if (syllabify_dfs(&task, 0, phrase->start() - start_)) {
    result.set_vertices(std::move(vertices));
  }
  return result;
}

size_t ScriptSyllabifier::BuildSyllableGraph(Prism& prism) {
  size_t consumed =
      (size_t)syllabifier_.BuildSyllableGraph(input_, prism, &syllable_graph_);
  // WhaleIME: 「桶内原样优先」的总开关 —— 图上一条纠错边都没有时
  // （九键、双拼、关了纠错的方案）整条逻辑空转，热路径不额外做 DP。
  has_corrections_ = false;
  for (const auto& start : syllable_graph_.edges) {
    for (const auto& seg : start.second) {
      for (const auto& spelling : seg.second) {
        if (spelling.second.is_correction) {
          has_corrections_ = true;
          break;
        }
      }
      if (has_corrections_)
        break;
    }
    if (has_corrections_)
      break;
  }
  return consumed;
}

bool ScriptSyllabifier::IsCorrection(const Code& code,
                                     size_t code_length) const {
  vector<bool> path_attributes;
  path_attributes.reserve(8);

  SyllabifyTask task{
      code, syllable_graph_, code_length,
      // push
      [&](SyllabifyTask* task, size_t depth, size_t current_pos,
          size_t next_pos) {
        auto id = task->code[depth];

        auto start_iter = syllable_graph_.edges.find(current_pos);
        if (start_iter != syllable_graph_.edges.end()) {
          auto end_iter = start_iter->second.find(next_pos);
          if (end_iter != start_iter->second.end()) {
            auto prop_iter = end_iter->second.find(id);
            if (prop_iter != end_iter->second.end()) {
              path_attributes.push_back(prop_iter->second.is_correction);
              return;
            }
          }
        }
        // edge not found
        path_attributes.push_back(false);
      },
      // pop
      [&](SyllabifyTask* task, size_t depth) { path_attributes.pop_back(); }};

  if (syllabify_dfs(&task, 0, 0)) {
    for (bool is_correction : path_attributes) {
      if (is_correction)
        return true;
    }
  }
  return false;
}

size_t ScriptSyllabifier::MeasureExactBytes(const Code& code,
                                            size_t code_start,
                                            size_t code_end,
                                            bool* found,
                                            size_t* edits) const {
  if (found)
    *found = false;
  if (edits)
    *edits = 0;
  if (code.empty() || code_end <= code_start)
    return code_end > code_start ? code_end - code_start : 0;
  const size_t width = code_end + 1;
  vector<PathScore> memo((code.size() + 1) * width);
  vector<char> known((code.size() + 1) * width, 0);
  const PathScore score =
      probe_path(syllable_graph_, code, 0, code_start, code_end, &memo, &known);
  if (score.ok) {
    if (found)
      *found = true;
    if (edits)
      *edits = score.edits;
    return score.exact;
  }
  // 预测词/补全词：编码比已输入的串长，走不到终点，按整段算原样、0 误按
  return code_end - code_start;
}

// WhaleIME: [pos, end_pos) 这段输入按「用户实际敲的键」能怎么切 —— 只走非纠错边。
// 长音节优先（与上游 preedit 的取向一致：jia 优先于 ji'a）。
bool ScriptSyllabifier::FaithfulSplit(size_t pos,
                                      size_t end_pos,
                                      vector<string>* parts) const {
  if (pos == end_pos)
    return true;
  auto edges = syllable_graph_.edges.find(pos);
  if (edges == syllable_graph_.edges.end())
    return false;
  for (const auto& seg : boost::adaptors::reverse(edges->second)) {
    const size_t next = seg.first;
    if (next <= pos || next > end_pos)
      continue;
    bool exact = false;
    for (const auto& spelling : seg.second) {
      if (!spelling.second.is_correction) {
        exact = true;
        break;
      }
    }
    if (!exact)
      continue;
    parts->push_back(input_.substr(pos, next - pos));
    if (FaithfulSplit(next, end_pos, parts))
      return true;
    parts->pop_back();
  }
  return false;
}

// WhaleIME: 分词（preedit）优先取「用户敲的键自己的精确切分」。
//
// 上游这里是按**候选的编码**做 DFS 打印输入的字节：候选是纠错来的（与科技啊 =
// yu'ke'ji'a，le→ke 改一个键），打印出来的分词就成了 yu'le'ji'a —— 用户从没敲过
// ji'a 这种切法，Kotlin 侧却拿 preedit 当分段依据（RimeEngine.segments() →
// 候选面板的分段选择、focusSegment 会 setinput(corrected syllable)），于是
// ① 分词栏显示一个不存在的切分；② 「jia」这段只在纠错读音里存在，聚焦后只剩
// 纠错限流放行的零星单字（用户看到「只有一个加字」）。
// 整段输入能被精确音节切完时，只有「改过键」的候选才会走到这里（原样候选的
// 编码本来就对应精确切分），此时改用精确切分呈现；纠错本身仍由候选的 `?` 角标
// 和 comment 表达（comment 里带的是候选真实读音，两者各司其职）。
string ScriptSyllabifier::GetPreeditString(const Phrase& cand) const {
  const auto& delimiters = translator_->delimiters();
  const size_t begin_pos = cand.start() - start_;
  const size_t end_pos = cand.end() - start_;
  if (end_pos > begin_pos) {
    bool found = false;
    const size_t exact =
        MeasureExactBytes(cand.matching_code(), begin_pos, end_pos, &found);
    if (found && exact < end_pos - begin_pos) {
      vector<string> parts;
      if (FaithfulSplit(begin_pos, end_pos, &parts))
        return translator_->FormatPreedit(
            boost::algorithm::join(parts, string(1, delimiters.at(0))));
    }
  }
  std::stack<size_t> lengths;
  string output;
  SyllabifyTask task{cand.matching_code(), syllable_graph_, cand.end() - start_,
                     [&](SyllabifyTask* task, size_t depth, size_t current_pos,
                         size_t next_pos) {
                       size_t len = output.length();
                       if (depth > 0 && len > 0 &&
                           delimiters.find(output[len - 1]) == string::npos) {
                         output += delimiters.at(0);
                       }
                       output +=
                           input_.substr(current_pos, next_pos - current_pos);
                       lengths.push(len);
                     },
                     [&](SyllabifyTask* task, size_t depth) {
                       output.resize(lengths.top());
                       lengths.pop();
                     }};
  if (syllabify_dfs(&task, 0, cand.start() - start_)) {
    return translator_->FormatPreedit(output);
  } else {
    return string();
  }
}

string ScriptSyllabifier::GetOriginalSpelling(const Phrase& cand) const {
  if (translator_ &&
      static_cast<int>(cand.code().size()) <= translator_->spelling_hints()) {
    return translator_->Spell(cand.code());
  }
  return string();
}

// ScriptTranslation implementation

bool ScriptTranslation::Evaluate(Dictionary* dict, UserDictionary* user_dict) {
  const bool stats = CorrStatsEnabled();
  const double t_begin = stats ? CorrNowMs() : 0;
  double t_sentence = 0;
  size_t consumed = syllabifier_->BuildSyllableGraph(*dict->prism());
  const double t_syllabify = stats ? CorrNowMs() - t_begin : 0;
  const auto& syllable_graph = syllabifier_->syllable_graph();
  bool predict_word = translator_->enable_word_completion() &&
                      start_ + consumed == end_of_input_;

  double t0 = stats ? CorrNowMs() : 0;
  phrase_ =
      dict->Lookup(syllable_graph, 0, &translator_->blacklist(), predict_word);
  if (user_dict) {
    const size_t kUnlimitedDepth = 0;
    const size_t kNumSyllablesToPredictWord = 4;
    user_phrase_ =
        user_dict->Lookup(syllable_graph, 0, kUnlimitedDepth,
                          predict_word ? kNumSyllablesToPredictWord : 0);
  }
  const double t_lookup = stats ? CorrNowMs() - t0 : 0;
  if (!phrase_ && !user_phrase_) {
    if (stats) {
      fprintf(stderr,
              "[trans] syl=%.2f lookup=%.2f sent=0.00 slots=0 empty\n",
              t_syllabify, t_lookup);
    }
    return false;
  }

  consumed_ = consumed;
  // WhaleIME: 「桶内原样优先」的前提 —— 整段输入能被精确音节切完（"beng" / "xi" /
  // "jifang"），且音节图表上确实有纠错边（没有纠错边的方案直接跳过整套逻辑）。
  exact_full_coverage_ =
      syllabifier_->has_corrections() &&
      HasExactFullCoverage(syllable_graph, consumed);
  // WhaleIME: 输入能否被「完整拼写」的音节切完（简拼不算）。为 false 说明用户
  // 敲的键不是一句合法拼音：纠错条目要提前（见 HoistCorrections），简拼串里的
  // 「编码长度 == 输入长度」的改键条目也不再让位（见 TakeExactEntry）。
  table_ = dict->tables().empty() ? nullptr : dict->primary_table().get();
  const bool full_spelling_coverage =
      table_ && HasFullSpellingCoverage(syllable_graph, consumed, table_);
  // WhaleIME 2026-11-09: 「简拼串」（shyzi = sh|y|zi）—— 整段输入能靠缩写边切完，
  // 但用户几乎没把音节拼出来（完整拼写只覆盖不到一半按键）。这种输入里桶内次序
  // 要按词频走（见 TakeExactEntry）：
  //
  //   桶 end_pos=5 的词典次序（含 abbreviation / 纠错惩罚）是
  //     属于自己 6.35 / 数字 5.44 / 实用主义 4.41 … 啥样子 3.27 … 十一子 1.10
  //   而旧的「原样优先」把 数字（编码长度 == 输入长度，只错一个键）当成改键条目
  //   扣到 deferred，于是 啥样子 / 树叶子 / 十一子 这些「简拼凑出来」的低频词
  //   排在它前面，数字掉到 20 名开外。
  //
  // 门槛卡在「完整拼写覆盖 < 一半」而不是「完全不能完整拼写」：末尾误按的输入
  // （dapiyinf = da'pi'yin + f）完整拼写覆盖 7/8，仍属「合法拼音 + 一个错键」，
  // 必须保留原样优先 —— 放开它实测误按语料 top5 154 → 124（见 C5 的教训）。
  const size_t spelled_bytes =
      table_ ? MaxFullSpellingEnd(syllable_graph, consumed, table_) : 0;
  abbrev_input_ = exact_full_coverage_ && spelled_bytes * 2 < consumed;
  // WhaleIME 2026-11-14: 「尾部悬空」（xianf / xianh → 想）——
  // 整段输入能被精确边切完（xian + f 的缩写边），所以 exact_full_coverage_ 为 true、
  // 桶内「原样优先」生效；但末键 f 拼不出任何完整音节（完整拼写只到 4/5），桶里那
  // 五十多条「原样」条目全是靠缩写把末键补全出来的猜测：先发(xian'fa) / 宪法(xian'fa)
  // / 掀翻(xian'fan) / 先富(xian'fu)…。它们对「末键到底想打什么」的判断，与「把 f
  // 当成 g、整串读作 xiang」的纠错是同一件未知事，却因为原样优先被整批排到纠错前面，
  // 想（freq 是 先锋 的 40 倍）因此沉到第 53 位。
  // 判据就用 spelled_bytes < consumed：末尾还有没被完整音节吃掉的按键。
  // 与 abbrev_input_ 分开：那时整串都靠缩写（shyzi），桶内次序另有 fixed_cover 一套；
  // 全拼输入（beng / xi / jifang，spelled_bytes == consumed）不受影响，仍走原样优先
  // —— 「本」压「崩」那个 badcase（C1）正是靠它。
  // 注意这里不引入「按词频整体重排」：跨编码的词频不可比，且 beng 的 本/能 词频
  // 远高于 崩，纯词频排序会把 C1 打回去。放开原样优先后，桶内次序仍是词典给出的
  // 「词频 + 类型先验（缩写 ≈ −0.7、每个误按键 −4.6）」，两边的猜测就此公平竞争。
  hanging_tail_ = exact_full_coverage_ && !abbrev_input_ && spelled_bytes < consumed;
  // WhaleIME 2026-11-14: 单音节输入（ke / beng / xi）—— 见 TakeExactEntry 的裕度归并。
  single_syllable_ =
      exact_full_coverage_ && HasSingleSyllableCoverage(syllable_graph, consumed);
  // 只在极短输入上动手：1~2 个音节的串候选本就成片（lsi 有 80+ 条简拼双字词），
  // 纠错条目被彻底淹没；长输入里正确答案通常已经在前几条，插进来只会把它挤后一位
  // （320 条单键误按语料实测：不限长度时 top5 148→87，限到 4 字节后回到基线）。
  const size_t kHoistMaxInputBytes = 4;
  hoist_corrections_ = syllabifier_->has_corrections() && table_ &&
                       consumed <= kHoistMaxInputBytes &&
                       !full_spelling_coverage;
  // 句首 + 满覆盖 + 多种满覆盖切分 ⇒ 开启切分先验重排（见 LoadSlotHead）。
  // 非句首时 ContextualTranslation 会带着真实前文走 octagram，不需要这里插手。
  if (enable_segmentation_prior_ && grammar_) {
    use_sentence_start_gram_ =
        translator_->GetPrecedingText(start_).empty() &&
        HasMultipleExactPaths(syllable_graph, consumed);
  }

  InitSlots();

  // 上游用「最长分桶的首条候选」判断是否存在严格整词覆盖
  auto longest_head_code = [](auto& collector, size_t* length) -> const Code* {
    if (!collector)
      return nullptr;
    auto iter = collector->rbegin();
    if (iter == collector->rend() || iter->second.exhausted())
      return nullptr;
    *length = iter->first;
    return &iter->second.Peek()->code;
  };

  size_t sys_len = 0;
  size_t user_len = 0;
  const Code* sys_code = longest_head_code(phrase_, &sys_len);
  const Code* user_code = longest_head_code(user_phrase_, &user_len);

  bool has_reliable_phrase =
      sys_code && sys_len == consumed &&
      !syllabifier_->IsCorrection(*sys_code, consumed);
  bool has_reliable_user_phrase =
      user_code && user_len == consumed &&
      !syllabifier_->IsCorrection(*user_code, consumed);

  bool has_at_least_two_syllables = syllable_graph.edges.size() >= 2;
  DLOG(INFO) << "consumed: " << consumed
             << ", has_reliable_phrase: " << has_reliable_phrase
             << ", has_reliable_user_phrase: " << has_reliable_user_phrase
             << ", has_at_least_two_syllables: " << has_at_least_two_syllables;
  // make sentences when there is no exact-matching phrase candidate
  //
  // WhaleIME: 这里回到上游的条件 —— 不再因为「整段输入已被纠错词覆盖」就跳过组句。
  // 旧补丁是为了修 "zhonguo" 的垃圾组句，代价是把本该由组句给出的正确答案
  // （zhebuhaoba→这不好吧 / zhebuhes→这不合适 / nibuneng→你不能）一起干掉了。
  // 组句现在与纠错词一起按 RankKey 排序（见文件头），"zhonguo" 的 中或 压不过
  // 中国，不需要再用布尔条件一刀切。
  if (has_at_least_two_syllables && !has_reliable_phrase &&
      !has_reliable_user_phrase) {
    const double t_sent0 = stats ? CorrNowMs() : 0;
    if (max_sentences_ > 1)
      sentences_ = MakeSentences(dict, user_dict);
    else if (max_sentences_) {
      auto sentence = MakeSentence(dict, user_dict);
      if (sentence)
        sentences_ = {sentence};
      else
        sentences_.clear();
    }
    if (stats)
      t_sentence = CorrNowMs() - t_sent0;
  } else {
    t_sentence = 0;
  }

  if (stats) {
    fprintf(stderr, "[trans] syl=%.2f lookup=%.2f sent=%.2f slots=%zu\n",
            t_syllabify, t_lookup, t_sentence, slots_.size());
  }

  return !CheckEmpty();
}

void ScriptTranslation::InitSlots() {
  slots_.clear();
  // 用户词优先入列：键完全相同时先入列者胜出（与上游「同编码长度优先用户词」一致）
  if (user_phrase_) {
    for (auto& v : *user_phrase_) {
      Slot slot;
      slot.end_pos = v.first;
      slot.usr = &v.second;
      slots_.push_back(slot);
    }
  }
  if (phrase_) {
    for (auto& v : *phrase_) {
      Slot slot;
      slot.end_pos = v.first;
      slot.sys = &v.second;
      slots_.push_back(slot);
    }
  }
  if (use_sentence_start_gram_) {
    for (auto& slot : slots_) {
      if (slot.end_pos == consumed_)
        RerankFullCoverageSlot(&slot);
    }
  } else if (hoist_corrections_) {
    for (auto& slot : slots_) {
      if (slot.end_pos == consumed_)
        HoistCorrections(&slot);
    }
  } else if (abbrev_input_) {
    for (auto& slot : slots_) {
      if (slot.end_pos == consumed_)
        RerankAbbrevSlot(&slot);
    }
  }
  // WhaleIME 2026-11-09: 简拼串（shyzi）的桶里，原样条目与改键条目按词频混排，
  // 桶头可能是改键条目（数字，cover 4），而同一个桶后面还躺着 cover 5 的简拼词
  // （啥样子）。RankKey 是按**桶头**算 cover 的，于是这个桶会时高时低：
  //   数字 出来之后桶头变回 啥样子（cover 5），可组句「是影子」和 end_pos=4 的
  //   补全词都在 cover 5/4 上跟它打平，一有抖动就把数字挤到后面、把简拼词冲散。
  // 这里把「桶里还有原样条目」的桶的 cover 固定成它覆盖到的位置：桶的地位由
  // **覆盖到哪儿**决定，不随桶头是原样还是改键而跳动（桶内次序仍由词频决定）。
  // 只在简拼串上生效 —— 全拼/末尾误按输入的桶头恒为原样条目，改键条目本来
  // 就要沉到别的桶之后。
  if (abbrev_input_) {
    vector<char> reachable;
    MarkExactReachable(syllabifier_->syllable_graph(), consumed_, &reachable);
    for (auto& slot : slots_) {
      if (slot.end_pos < reachable.size() && reachable[slot.end_pos])
        slot.fixed_cover = (double)slot.end_pos;
    }
  }
}

// WhaleIME 2026-11-19: 简拼串满覆盖桶的桶内次序 = 词条权重 − 拼写长度偏差。
//
// 背景：cover = 解释到的字节数 − 误按键数 是跨桶的主键（见 RankKey），桶内次序
// 由词条权重给出（weight 里已经含了误按惩罚 ln(0.01) × 误按键数）。这套结构
// 对**全拼**输入成立：cover 与「原样优先」共同保证「用户真敲出来的那个词」先出。
//
// 但在**简拼串**（abbrev_input_：整段只能靠缩写边切完，MaxFullSpellingEnd 不到
// 输入长度的一半）里，桶里的「原样」条目本身也只是缩写猜测 ——
//   lsishi 的 临死时 = l|si|shi（l 是 lin 的缩写，0 误按）
//   shgyici 的 试过一次 = sh|g|yi|ci（两个单字母缩写，0 误按）
// 它们和「改过一两个键的真词」（老师 lao'shi、上一次 shang'yi'ci）是同一类
// 猜测，没有资格靠「0 误按」整批排到前面：实测 临死时 的词频只有 老师 的
// 1/1900，稳坐第一；上一次 更是被挤出前 300。
//
// 所以这个桶改成按「词频 + 类型先验（缩写 −0.69、补全 −3.0）− 拼写长度偏差」
// 排。**偏差**是「词条的字母数 − 这段输入的按键数」的绝对值：
//   差 0（好的 hao'de 5:5、老师 lao'shi 6:6）＝ 每个字母都对应一个按键，
//        用户确实在按字母拼这个词，只是有几个键敲歪了；
//   差 2（临死时 8:6、上一次 shang'yi'ci 9:7）＝ 少打了两个字母，
//        多出来的选择没有「按键」作依据。
// 这一项同时取代了原来的「原样优先」，是「这条解释有没有按键依据」的连续版本。
// 与九键切分先验、短输入提前（HoistCorrections）互斥（那两条优先）；桶内不再做
// 「原样优先」，见 TakeExactEntry 里 abbrev_input_ 的分支，两边口径必须一致。
void ScriptTranslation::RerankAbbrevSlot(Slot* slot) {
  const size_t kLimit = 64;
  if (slot->rerank_done || !slot->sys)
    return;
  slot->rerank_done = true;

  // 每个「字母数与按键数之差」的代价。取 1.5（≈ e^1.5 = 4.5 倍词频）：
  // 少打两个字母还能靠词频补回来（上一次 9:7 只扣 3.0），但「全是缩写」的
  // 长词（试过一次 10:7 扣 4.5）就压不过按键一一对应的真词了。
  constexpr double kDeviationPenalty = 1.5;

  struct Item {
    an<DictEntry> entry;
    double score = 0.0;
    bool full_span = false;  // 编码能在这段输入里完整走完（非预测词）
  };
  vector<Item> items;
  while (items.size() < kLimit && !slot->sys->exhausted()) {
    an<DictEntry> entry = slot->sys->Peek();
    if (!entry)
      break;
    slot->sys->Next();
    Item item;
    item.entry = entry;
    bool found = false;
    // 误按键数用不到（weight 里已经算过），但 probe 的成功与否要用来识别预测词。
    syllabifier_->MeasureExactBytes(entry->code, 0, slot->end_pos, &found);
    item.full_span = found;
    // probe 失败（预测词：编码比已输入的串长）时不改权重 —— 这类条目只解释了
    // 前缀，排在 full_span 之后，见下面的排序。
    item.score = entry->weight;
    if (found) {
      const size_t spelled = CodeSpellingLength(entry->code);
      const size_t deviation = spelled > slot->end_pos
                                   ? spelled - slot->end_pos
                                   : slot->end_pos - spelled;
      item.score -= kDeviationPenalty * (double)deviation;
    }
    items.push_back(item);
  }
  std::stable_sort(items.begin(), items.end(),
                   [](const Item& a, const Item& b) {
                     if (a.full_span != b.full_span)
                       return a.full_span;
                     return a.score > b.score;
                   });
  slot->reranked.clear();
  slot->reranked.reserve(items.size());
  for (auto& item : items)
    slot->reranked.push_back(item.entry);
}

// WhaleIME: 把满覆盖桶里「改过键、且编码正好覆盖整段输入」的条目提到桶头。
//
// 只用于「输入拼不出完整拼写」的桶（lsi / xianlau 这类敲错键的串，见
// hoist_corrections_）：这种桶里成片的是靠简拼 + 补全凑出来的长词
// （lsi → l|si → 类似/罗斯/螺丝…，编码 6 字节却只覆盖输入的 3 字节），
// 它们按**编码顺序**排在前面，把唯一「编码跟输入一样长、只差敲错的那一个键」
// 的纠错条目（lai → 来）压到几十名开外，用户翻不到，等于没有纠错。
//
// 为什么按结构而不是按词频：词典里 132 万条词条的 weight 是 100 占位档，
// 跨编码没有可比性（见 RerankFullCoverageSlot 的说明），只能靠「编码长度是否
// 正好等于输入长度」这种结构信息来分先后。
//
// 提前的条目**按音节分组、每组取前 kPerGroup 条**：一次误按往往有多个可能方向
// （bsi 里的 s 可能本意是 a / e / d / w / z / x，对应 bai / bei / bde… ），
// 每个方向都是不同的音节组。只按桶内顺序取前 N 条会全落在前两个组里，
// 另一组（bsi 的 bai → 白/百）永远露不出来。同组取两条是为了让「白/百」这类
// 同音字一起出现。
//
// 合法的全拼输入（beng / zhongguo）不进这里：那类输入由「原样优先」管顺序。
void ScriptTranslation::HoistCorrections(Slot* slot) {
  const size_t kLimit = 1024;
  const size_t kPerGroup = 2;
  const size_t kGroupLimit = 4;  // 最多 4 个误按方向 × 2 条 = 8 条
  if (slot->rerank_done || !slot->sys)
    return;
  slot->rerank_done = true;

  struct Pick {
    an<DictEntry> entry;
    bool faithful = true;
    bool covers_input = false;
  };
  vector<Pick> picks;
  while (picks.size() < kLimit && !slot->sys->exhausted()) {
    an<DictEntry> entry = slot->sys->Peek();
    if (!entry)
      break;
    slot->sys->Next();
    Pick pick;
    pick.entry = entry;
    bool found = false;
    const size_t exact =
        syllabifier_->MeasureExactBytes(entry->code, 0, slot->end_pos, &found);
    pick.faithful = found && exact >= slot->end_pos;
    // 编码总长 == 满覆盖长度：这条词真的只差「敲错的那几个键」，而不是靠简拼
    // 或补全把剩下的字节凑出来的长词（老师 lao'shi 这种对 lsi 就是纯猜测）。
    pick.covers_input = table_ && CodeSpellingLength(entry->code) == slot->end_pos;
    picks.push_back(pick);
  }

  vector<an<DictEntry>> head, hoisted, rest;
  bool head_taken = false;
  // 按「首个音节 id」分组计数：同一误按方向的词条在桶内是连续的，各自占一个组。
  vector<pair<SyllableId, size_t>> groups;
  groups.reserve(kGroupLimit);
  for (auto& pick : picks) {
    if (!head_taken && pick.faithful) {
      // 原序第一条原样条目（lsi 的「类似」这类简拼词）留在最前
      head.push_back(pick.entry);
      head_taken = true;
      continue;
    }
    if (!pick.faithful && pick.covers_input) {
      const SyllableId first =
          pick.entry->code.empty() ? 0 : pick.entry->code.front();
      size_t* count = nullptr;
      for (auto& group : groups) {
        if (group.first == first) {
          count = &group.second;
          break;
        }
      }
      if (!count) {
        if (groups.size() >= kGroupLimit) {
          rest.push_back(pick.entry);
          continue;
        }
        groups.push_back({first, 0});
        count = &groups.back().second;
      }
      if (*count < kPerGroup) {
        ++*count;
        hoisted.push_back(pick.entry);
        continue;
      }
    }
    rest.push_back(pick.entry);
  }

  // 插入位置：保留前 kHeadKeep 条原样条目，再把提前的纠错条目放出来。
  // 放太前（紧跟第一条）会把「简拼正确」的输入挤掉 —— 320 条单键误按语料实测
  // top5 从 148 掉到 84；放到第 5 条（一页内）才既保证纠错可见，又不动前四条。
  const size_t kHeadKeep = 4;
  vector<an<DictEntry>> ordered;
  ordered.reserve(picks.size());
  for (auto& entry : head)
    ordered.push_back(entry);
  size_t kept = 0;
  size_t rest_pos = 0;
  while (kept < kHeadKeep && rest_pos < rest.size()) {
    ordered.push_back(rest[rest_pos++]);
    ++kept;
  }
  for (auto& entry : hoisted)
    ordered.push_back(entry);
  while (rest_pos < rest.size())
    ordered.push_back(rest[rest_pos++]);
  if (ordered.empty())
    return;
  slot->hoisted = hoisted;
  for (auto& entry : ordered)
    slot->reranked.push_back(entry);
}

// 把满覆盖桶的头部若干条重排：**先按切分、再按词条权重**。
//
// 为什么是这个次序：跨音节切分的候选（zhe'bu'hao vs yi'da'tiao）分属不同编码，
// 而 132 万条词条权重是 100 占位，跨切分比词条权重没有意义；同一切分内部词条
// 权重才是可信的。所以用 n-gram 分裁决「哪种切分更像话」（取该切分下最好的
// 一条作为切分先验），切分内部继续按词条权重排，两个量各自用在能用的地方，
// 不做量纲混算。
//
// 只处理系统词库桶：用户词有明确的用户偏好，不该被语言模型压下去。
void ScriptTranslation::RerankFullCoverageSlot(Slot* slot) {
  const size_t kRerankLimit = 64;
  if (slot->rerank_done)
    return;
  slot->rerank_done = true;
  if (!slot->sys)
    return;

  struct Item {
    an<DictEntry> entry;
    string seg;
    double gram = 0.0;
    double weight = 0.0;
    bool predictive = false;
  };
  // 「切分」= 这条候选在输入上的音节边界位置，不是音节 id。
  // 九键里 zhe 与 xie 的键码都是 WGD，写不好 与 这不好 属于**同一个**切分
  // （边界 3/5/8），必须归到一组，否则同组内的词条权重比较会失效。
  const SyllableGraph& graph = syllabifier_->syllable_graph();
  vector<size_t> ends;
  vector<Item> items;
  while (items.size() < kRerankLimit && !slot->sys->exhausted()) {
    an<DictEntry> entry = slot->sys->Peek();
    if (!entry)
      break;
    Item item;
    item.entry = entry;
    item.weight = entry->weight;
    // 补全/预测词的编码比已输入的长（做出来的 → 做出来的事情），文本多出来的
    // 部分没有对应的按键，切分与 n-gram 分都不可比。这类条目保持原位置不动。
    item.predictive = entry->IsPredictiveMatch();
    if (!item.predictive &&
        CollectSyllableEnds(graph, entry->code, slot->end_pos, &ends)) {
      item.seg.assign(reinterpret_cast<const char*>(ends.data()),
                      ends.size() * sizeof(size_t));
    }
    // 拿不到 n-gram 分（词太长 / 库里没这条）时给最低分，切分先验随之沉底
    if (!SentenceStartGramScore(grammar_.get(), entry->text, &item.gram))
      item.gram = kNoGramEvidence;
    items.push_back(item);
    slot->sys->Next();
  }

  map<string, double> seg_prior;
  map<string, double> seg_head_weight;
  for (const auto& item : items) {
    if (item.predictive)
      continue;
    auto it = seg_prior.find(item.seg);
    if (it == seg_prior.end()) {
      seg_prior.emplace(item.seg, item.gram);
      seg_head_weight.emplace(item.seg, item.weight);
    } else {
      if (item.gram > it->second)
        it->second = item.gram;
      double& head = seg_head_weight[item.seg];
      if (item.weight > head)
        head = item.weight;
    }
  }
  // 只有「某个切分里最好的词条本身就是 100 占位档」时才动顺序：那种情况下
  // 拿它的词条权重去和其他切分比是无效比较（占位档没有信息）。如果每个切分的
  // 最好词条都有真实词频，词条权重本来是可信的，保持词典原序，不要用 n-gram
  // 分去覆盖它——26 键实测这一条是主要退化来源。
  bool weight_untrustworthy = false;
  for (const auto& kv : seg_head_weight) {
    if (kv.second <= kPlaceholderWeight + 1e-6) {
      weight_untrustworthy = true;
      break;
    }
  }
  slot->reranked.clear();
  slot->reranked.reserve(items.size());
  if (!weight_untrustworthy) {
    for (auto& item : items)
      slot->reranked.push_back(item.entry);
    return;
  }
  // 只重排非预测词，预测词留在原来的位置上（见上面 predictive 的说明）
  vector<Item*> movable;
  movable.reserve(items.size());
  for (auto& item : items) {
    if (!item.predictive)
      movable.push_back(&item);
  }
  std::stable_sort(movable.begin(), movable.end(),
                   [&seg_prior](const Item* a, const Item* b) {
                     const double pa = seg_prior[a->seg];
                     const double pb = seg_prior[b->seg];
                     if (pa != pb)
                       return pa > pb;
                     if (a->weight != b->weight)
                       return a->weight > b->weight;
                     return a->gram > b->gram;
                   });
  size_t next = 0;
  for (auto& item : items) {
    slot->reranked.push_back(item.predictive ? item.entry
                                              : movable[next++]->entry);
  }
}

bool ScriptTranslation::SlotHasEntry(const Slot& slot) const {
  if (slot.pending)
    return true;
  if (slot.reranked_pos < slot.reranked.size())
    return true;
  if (slot.deferred_pos < slot.deferred.size())
    return true;
  return slot.sys ? !slot.sys->exhausted() : !slot.usr->exhausted();
}

// 从槽的底层流里取下一条词条，并把游标前移（槽对外的 head 状态由调用方维护）。
an<DictEntry> ScriptTranslation::TakeRawEntry(Slot* slot) {
  an<DictEntry> entry;
  if (slot->reranked_pos < slot->reranked.size()) {
    entry = slot->reranked[slot->reranked_pos++];
    return entry;
  }
  if (slot->sys) {
    entry = slot->sys->Peek();
    slot->sys->Next();
    return entry;
  }
  if (slot->usr) {
    entry = slot->usr->Peek();
    slot->usr->Next();
    return entry;
  }
  return nullptr;
}

// WhaleIME: 取「原样解释」的条目 —— 途中遇到的改键条目先扣到 deferred，再按词频
// 带偏置归并回来（见下面 kFaithfulFirstMargin）。
//
// 为什么必须在这里动手：一个 end_pos 桶同时装着原样词条与改键词条，桶内次序由词典
// 给出（词条权重 + 纠错惩罚），RankKey 只能决定桶与桶之间、以及桶头的先后，改不动
// 桶内次序。所以在「整段输入能被精确音节切完」时，改键条目要先垫后、按偏置插队。
//
// 判定要求 probe **成功**（found）才算原样：编码比输入长/只匹配了前缀的词条
// （词典里「做你该做的事」这类被简拼凑到满覆盖的长词，matching_code_size < code.size()）
// probe 会走不到终点，此时 MeasureExactBytes 退回「整段算原样」——那个兜底是给
// 补全候选用的，拿来当「原样」会把这类长词顶到桶头。这里一律按改键条目处理。
//
// WhaleIME 2026-11-09: 「简拼串」（abbrev_input_）里放行「编码长度正好等于覆盖长度」
// 的改键条目，不让它们进 deferred —— 见 Evaluate 里 abbrev_input_ 的说明。
//
// WhaleIME 2026-11-14: 「尾部悬空」（hanging_tail_，xianf / xianh）的**满覆盖桶**也不扣
// —— 这个桶里的「原样」条目本身就是靠缩写补全末键的猜测（先发 / 宪法 / 掀翻…），把
// 纠错扣到它们后面等于「缩写猜测无条件赢过误按猜测」，与 C1 的前提（用户真的把音节打
// 全了）无关。只放开这一个桶：end_pos < consumed_ 的桶根本没覆盖到悬空的末键，那里
// 「原样」是真按用户敲的键解释出来的，原样优先照旧生效（放开全部桶实测会把
// huizhuanhus / junshiguojiq 里本该排第一的纠错挤到 20 名开外）。
// WhaleIME 2026-11-14：「原样优先」的力度 —— 改键条目要在**词频**上比原样条目高出
// 这么多（自然对数，0.5 ≈ 1.65 倍）才允许插到它前面。只在单音节输入的满覆盖桶里生效，
// 多音节输入仍是「原样条目全出完才轮到纠错」。见 TakeExactEntry。
constexpr double kFaithfulFirstMargin = 0.5;
// 不参与归并的桶用这个值当裕度：任何词条都不可能高出它，等价于「原样全出完再放纠错」。
constexpr double kNoMergeMargin = 1e9;

an<DictEntry> ScriptTranslation::TakeExactEntry(Slot* slot) {
  const bool hanging_tail_bucket = hanging_tail_ && slot->end_pos == consumed_;
  if (!exact_full_coverage_ || hanging_tail_bucket)
    return TakeRawEntry(slot);  // 输入本身拼不出合法音节 / 末尾悬空：保持词典次序
  // WhaleIME 2026-11-19: 简拼串的满覆盖桶已经由 RerankAbbrevSlot 按词频排好，
  // 这里的「原样优先」必须让开 —— 否则刚排到前面的改键条目（老师 / 上一次）又被
  // 扣进 deferred，等整桶的缩写猜测出完才轮到它，等于没排。两边是同一件事的两半：
  // 简拼串里「原样」只是缩写猜测，与「改键」在结构上没有区别，只按词频裁决。
  // 短输入（≤4 字节）走 HoistCorrections 那条路径，不在这里。
  if (abbrev_input_ && !hoist_corrections_ && slot->end_pos == consumed_)
    return TakeRawEntry(slot);
  // 裕度归并只在「单音节输入的满覆盖桶」里开：那个桶里没有词，全是这个音节的同音字
  // （ke → 可/课/克/科/氪…），把纠错整批扣到整份同音字表之后等于隔离，词频信号全被
  // 结构规则盖住（了 的词频是同音节绝大多数字的数倍，却排到 50 名开外）。
  // 多音节输入不开：那里的「原样」是整词精确匹配，词典里没有词频的占位词条
  // （霍总 @ −54、卷住、遮人）全靠原样优先才排得上来，放开就会被常见词的纠错
  // （汇总 活动 / 建筑 / 责任）顶掉 —— 320 条合法语料实测 26 条变坏。
  const double margin =
      (single_syllable_ && slot->end_pos == consumed_) ? kFaithfulFirstMargin
                                                       : kNoMergeMargin;
  while (true) {
    // 上一条原样条目被改键条目插了队：这一轮先把它交出去（不能丢，见下面的注释）。
    if (slot->pending) {
      an<DictEntry> pending = slot->pending;
      while (slot->deferred_pos < slot->deferred.size() &&
             slot->deferred[slot->deferred_pos]->weight - margin >
                 pending->weight)
        return slot->deferred[slot->deferred_pos++];
      slot->pending.reset();
      return pending;
    }
    an<DictEntry> entry = TakeRawEntry(slot);
    if (!entry)
      return nullptr;
    // 已被 HoistCorrections 提到桶头的纠错条目：按提前后的位置直接吐，
    // 不能再扣到 deferred —— 那等于把提前白做了（lsi 的来会退回几十名开外）。
    for (const auto& hoisted : slot->hoisted) {
      if (hoisted == entry)
        return entry;
    }
    bool found = false;
    const size_t exact =
        syllabifier_->MeasureExactBytes(entry->code, 0, slot->end_pos, &found);
    if (found && exact >= slot->end_pos) {
      // 原样条目：先放行「词频已经明显高过它的」暂存改键条目，再吐自己。
      // 两个流各自按词频降序，所以这里就是一次带偏置的归并：改键条目要赢过
      // 当前原样条目至少 margin 才插队。ke → 了 就是靠这一条
      // （了 14.38 − 4.6 = 9.78 赢不过 可 11.53 / 课 10.30 / 克 9.50，但赢得过 氪 8.11）。
      if (slot->deferred_pos < slot->deferred.size() &&
          slot->deferred[slot->deferred_pos]->weight - margin >
              entry->weight) {
        // 这条原样条目先存起来、下一轮吐它。不能直接丢掉：它已经从底层流里取走了，
        // 丢一次就等于少一条候选（实测 huozong 的 霍总/火鬃 会整条消失）。
        slot->pending = entry;
        return slot->deferred[slot->deferred_pos++];
      }
      return entry;
    }
    // 简拼串：这条词条的拼写长度正好等于它覆盖的按键数，说明它没靠缩写扩写、
    // 只是把敲错的键当成别的音节（数字 shu'zi ← shyzi，错 1 键）。这类词与简拼
    // 猜测（啥样子 sha'yang'zi）是同一层级的解释，按桶内词频次序直接吐出。
    if (abbrev_input_ && entry->code.size() && table_ &&
        CodeSpellingLength(entry->code) == slot->end_pos)
      return entry;
    // 改键条目：先扣着，等原样条目掉到它的词频以下再放（见上面的归并）。暂存上限
    // 与全局纠错限流一致，多出来的本来也会在 Next() 里被限流丢掉。
    if (slot->deferred.size() < correction_limit())
      slot->deferred.push_back(entry);
  }
}

// 候选的编码是否「改过用户敲的键」。
// 用 MeasureExactBytes（取「最多能原样解释多少字节」）而不是 IsCorrection（取
// syllabify_dfs 找到的第一条路径）：同一编码存在多条可行路径时两者可能给出不同
// 答案，而桶内排序、问号角标、纠错限流必须是同一把尺子。
bool ScriptTranslation::EndsWithCorrection(const Phrase& cand) const {
  const size_t covered = cand.end() - start_;
  if (covered == 0)
    return false;
  bool found = false;
  const size_t exact =
      syllabifier_->MeasureExactBytes(cand.code(), 0, covered, &found);
  // probe 失败（预测词：编码比已输入的串长）不算纠错 —— 它没有改动任何按键，
  // 不能因为「量不出来」就被限流/打角标，否则整类联想词会消失。
  return found && exact < covered;
}

// 预测词/补全词的编码比已输入的串长时，probe 走不到终点 → 按整段原样计（没有
// 对应的按键可改），与 MeasureExactBytes 的兜底口径一致。
double ScriptTranslation::Coverage(const Code& code,
                                   size_t code_start,
                                   size_t code_end) const {
  if (code_end <= code_start)
    return 0.;
  const size_t covered = code_end - code_start;
  bool found = false;
  size_t edits = 0;
  syllabifier_->MeasureExactBytes(code, code_start, code_end, &found, &edits);
  if (!found)
    return (double)covered;
  return covered > edits ? (double)(covered - edits) : 0.;
}

size_t ScriptTranslation::CodeSpellingLength(const Code& code) const {
  if (!table_)
    return 0;
  size_t length = 0;
  for (SyllableId syllable : code)
    length += table_->GetSyllableById(syllable).size();
  return length;
}

bool ScriptTranslation::LoadSlotHead(Slot* slot) {
  an<DictEntry> entry = TakeExactEntry(slot);
  if (!entry && slot->deferred_pos < slot->deferred.size())
    entry = slot->deferred[slot->deferred_pos++];
  if (!entry)
    return false;
  slot->head = entry;
  slot->head_valid = true;
  bool found = false;
  const size_t exact =
      syllabifier_->MeasureExactBytes(entry->code, 0, slot->end_pos, &found);
  const size_t covered = slot->end_pos;
  // 是否「原样解释」了这段输入 —— 见 TakeExactEntry 的说明。
  slot->faithful = found && exact >= covered;
  // cover = 解释到的字节数 − 误按键数（见文件头的 RankKey 说明）。改过键的条目按
  // 「改了几个键」扣分，与它改写了几个字节、拼写有多长无关，桶头因此能和精确桶
  // 放在同一把尺子上比。
  //
  // 例外只有一类：`HoistCorrections` 提前到桶头的改键条目（短输入 bsi → 被/白/是）。
  // 它们已经按「编码长度 == 输入长度 + 音节组」筛过一遍，桶内次序是结构性的，
  // cover 只需要把整个桶摆在「满覆盖精确桶」之后、短前缀桶之前 —— 若在这里再按
  // 误按次数折算，3 字节输入里错 2 个键就扣掉 2 字节，这些单字纠错会整组沉到
  // 几十名开外，提前等于白做。所以它们统一只扣半字节。
  bool hoisted_entry = false;
  for (const auto& hoisted : slot->hoisted) {
    if (hoisted == entry) {
      hoisted_entry = true;
      break;
    }
  }
  if (slot->fixed_cover > 0.) {
    // 简拼串的桶：桶的地位按「覆盖到哪儿」算，见 InitSlots 里 fixed_cover 的说明。
    slot->key.cover = slot->fixed_cover;
  } else if (hoisted_entry && !slot->faithful) {
    slot->key.cover = covered > 0.5 ? covered - 0.5 : covered;
  } else {
    slot->key.cover = Coverage(entry->code, 0, covered);
  }
  slot->key.end_pos = slot->end_pos;
  slot->key.parts = 1;
  // 桶内次序由 RerankFullCoverageSlot 决定（切分先验 → 词条权重），这里只保留
  // 桶头自身的词条权重，用于与同一 end_pos 上的用户词桶比较。
  slot->key.score = entry->weight;
  return true;
}

void ScriptTranslation::AdvanceSlot(Slot* slot) {
  // WhaleIME: head 在 LoadSlotHead 里已经从底层流取出（途中可能跳过纠错条目），
  // 这里只需要清掉「当前候选」状态，不能再推进迭代器。
  slot->head.reset();
  slot->head_valid = false;
}

RankKey ScriptTranslation::KeyOfSentence(const Sentence& sentence) const {
  RankKey key;
  key.end_pos = sentence.end() - start_;
  key.parts = sentence.size();
  key.score = sentence.weight();
  size_t pos = 0;
  const auto& words = sentence.components();
  const auto& lengths = sentence.word_lengths();
  // 组句的分数沿用 poet 的累积得分（Σ 对数词频 + Grammar::Evaluate）。
  // 试过改成纯 ΣlogP（去掉每词一次的语言模型惩罚）想让它和整词候选同量纲，
  // 结果 26 键直接退化："zhonguo" 的「中 或」（两词)靠 ΣlogP 压过了整词「中国」。
  // 整词与组句的可比性只能靠「整词优先」的结构（见 IsBetterKey 的 cover 主键）
  // 和词条权重本身，不能靠换分数体系。
  for (size_t i = 0; i < words.size() && i < lengths.size(); ++i) {
    const size_t start_pos = pos;
    pos += lengths[i];
    // 与整词候选同一把尺子：解释到的字节数 − 误按键数（见文件头的 RankKey 说明）。
    key.cover += Coverage(words[i].code, start_pos, pos);
  }
  return key;
}

bool ScriptTranslation::Next() {
  do {
    if (exhausted())
      return false;
    if (candidate_source_ == kUninitialized) {
      PrepareCandidate();  // to determine candidate_source_
    }
    switch (candidate_source_) {
      case kUninitialized:
        break;
      case kSentence:
        if (!sentences_.empty())
          sentences_.pop_front();
        sentence_key_valid_ = false;
        break;
      case kUserPhrase:
      case kSysPhrase:
        if (current_slot_ >= 0 && current_slot_ < (int)slots_.size())
          AdvanceSlot(&slots_[current_slot_]);
        break;
    }
    candidate_.reset();
    candidate_source_ = kUninitialized;
    current_slot_ = -1;
    if (enable_correction_) {
      // populate next candidate and skip it if it's a correction beyond max
      // numbers.
      if (!PrepareCandidate()) {
        break;
      }
    }
  } while (enable_correction_ && candidate_ &&
           EndsWithCorrection(*candidate_) &&
           // limit the number of correction candidates
           ++correction_count_ > correction_limit());
  if (!CheckEmpty() && candidate_) {
    ++candidate_index_;
    return true;
  }
  return false;
}

an<Candidate> ScriptTranslation::Peek() {
  if (candidate_source_ == kUninitialized && !PrepareCandidate()) {
    return nullptr;
  }
  if (candidate_->preedit().empty()) {
    candidate_->set_preedit(syllabifier_->GetPreeditString(*candidate_));
  }
  if (candidate_->comment().empty()) {
    auto spelling = syllabifier_->GetOriginalSpelling(*candidate_);
    // WhaleIME: 纠错候选在 comment 末尾追加 "?"，客户端据此渲染右上角角标。
    // 用后缀而非前缀：T9/双拼的 composition 转换按 "'" 切分 comment，
    // 前缀会污染第一个音节的比对，后缀只是多出一段无害的公共前缀。
    // 判定与桶内排序、纠错限流共用 EndsWithCorrection（见其注释）。
    bool is_correction = EndsWithCorrection(*candidate_);
    if (!spelling.empty()) {
      if (is_correction) {
        candidate_->set_comment(spelling + "?");
      } else if (translator_->always_show_comments() ||
                 spelling != candidate_->preedit()) {
        candidate_->set_comment(spelling);
      }
    } else if (is_correction) {
      candidate_->set_comment("?");
    }
  }
  candidate_->set_syllabifier(syllabifier_);
  return candidate_;
}

bool ScriptTranslation::PrepareCandidate() {
  if (exhausted()) {
    candidate_source_ = kUninitialized;
    candidate_ = nullptr;
    return false;
  }
  // 句子候选的排序键：队首只有一个，取用时算一次
  if (sentences_.empty()) {
    sentence_key_valid_ = false;
  } else if (!sentence_key_valid_) {
    sentence_key_ = KeyOfSentence(*sentences_.front());
    sentence_key_valid_ = true;
  }

  Slot* best = nullptr;
  for (auto& slot : slots_) {
    if (!SlotHasEntry(slot))
      continue;
    if (!slot.head_valid && !LoadSlotHead(&slot))
      continue;
    if (!best || IsBetterKey(slot.key, best->key))
      best = &slot;
  }

  // WhaleIME: 组句与整词「cover 打平」时，整词优先（组句是多个词的猜测，整词是一条
  // 词条）。yulejia 里 与科技啊（组句，cover 6，改了一个键）和 娱乐机（整词，cover 6）
  // 打平，旧次序靠 end_pos（7 > 6）把纠错组句排到了最前 —— 分词、读音栏于是全按
  // yu'ke'ji'a 这种用户没敲过的切分走。
  // 只在 cover 相同时生效：跨 cover 仍按 cover（呵护宝宝 7 < 这不好 9），组句该赢的
  // 场合（zhebuhaoba 的 这不好吧 cover 11）不受影响。
  // 九键（segmentation_prior 开启）不启用：一串按键能切出完全不同的音节，词数不可比，
  // 那里的同 cover 取舍只能交给概率（实测把词数当主键会让 一大跳 压过 这不好）。
  bool slot_wins = false;
  if (best) {
    if (sentences_.empty()) {
      slot_wins = true;
    } else if (!enable_segmentation_prior_ &&
               best->key.cover == sentence_key_.cover &&
               best->key.parts != sentence_key_.parts) {
      slot_wins = best->key.parts < sentence_key_.parts;
    } else {
      slot_wins = IsBetterKey(best->key, sentence_key_);
    }
  }
  if (slot_wins) {
    const auto& entry = best->head;
    DLOG(INFO) << "phrase '" << entry->text
               << "', end pos: " << best->end_pos;
    candidate_source_ = best->usr ? kUserPhrase : kSysPhrase;
    // WhaleIME: 改键候选单独标一个 type。ContextualTranslation 会把「同一 end_pos +
    // 同一 type」的连续候选取出来按 n-gram 权重重排（句首时等于按词条权重重排），
    // 那会把刚理好的桶内次序重新洗一遍（能 7.75 会跳回 崩 7.71 前面）。
    // 独立 type 让它们不参与这次重排、保持引擎次序。
    // 「提前的纠错条目」（HoistCorrections）同理：只有它们需要独立 type，
    // 没被提前的候选保持原 type，让 n-gram 重排照常发挥作用。
    bool hoisted_candidate = false;
    for (const auto& hoisted : best->hoisted) {
      if (hoisted == entry) {
        hoisted_candidate = true;
        break;
      }
    }
    const bool correction_candidate =
        (exact_full_coverage_ && !best->faithful) || hoisted_candidate;
    candidate_ = New<Phrase>(
        translator_->language(),
        correction_candidate
            ? "correction"
            : (entry->IsPredictiveMatch()
                   ? "completion"
                   : (best->usr ? "user_phrase" : "phrase")),
        start_, start_ + best->end_pos, entry);
    const size_t full_code_length = end_of_input_ - start_;
    double quality = std::exp(entry->weight) +
                     translator_->initial_quality() +
                     (entry->quality_len / full_code_length);
    // WhaleIME 2026-11-09（code → 全是纠错）: 候选跨译法器（table_translator@english）
    // 比较走 Candidate::compare：先比 end（越长越前），再比 quality。拼音的
    // initial_quality 是 1.2、english 是 1.1，于是「整串原样命中的英文词」永远排在
    // 所有满覆盖拼音候选之后 —— code 的 1~9 名全是 ci'de? / ce'de? 这类纠错，
    // 真正的 code 掉到第 10。改键候选（含提前的纠错条目）一律压到 initial_quality
    // 之下一点点（1.05）：原样拼音（≥1.2，简拼也算原样）不受影响；纠错仍然压过
    // english 的补全（1.1 − 1 = 0.1）与 cn_en（0.5），只是不再压过原样英文词。
    // 只在跨译法器比较里起作用，同一译法器内部的桶内次序不读 quality。
    // 同理，**整条编码没有任何全拼成分**（`quality_len == 0`，纯缩写/简拼猜测）的候选也
    // 不该压过原样命中的英文词：`ok` 的前 7 名是 欧克/欧卡/欧柯…（ou'ke 全靠 o|k 缩写猜），
    // 词库里的 ok 排到第 8；`gpu`/`cpu`/`usb` 这类简写词更是整个被挤出首屏。
    // 有全拼成分的候选（ni → 你，quality_len 拉满）不受影响，仍然排在英文之前。
    if (!best->faithful || entry->quality_len == 0.0)
      quality = std::min(quality, translator_->initial_quality() - 0.15);
    candidate_->set_quality(quality);
    current_slot_ = (int)(best - slots_.data());
    return true;
  }
  if (!sentences_.empty()) {
    candidate_source_ = kSentence;
    candidate_ = sentences_[0];
    return true;
  }
  candidate_source_ = kUninitialized;
  candidate_ = nullptr;
  return false;
}

bool ScriptTranslation::CheckEmpty() {
  bool empty = sentences_.empty();
  if (empty) {
    empty = true;
    for (const auto& slot : slots_) {
      if (SlotHasEntry(slot)) {
        empty = false;
        break;
      }
    }
  }
  set_exhausted(empty);
  return empty;
}

template <class QueryResult>
void ScriptTranslation::EnrollEntries(
    map<int, DictEntryList>& entries_by_end_pos,
    const an<QueryResult>& query_result) {
  if (query_result) {
    for (auto& y : *query_result) {
      DictEntryList& homophones = entries_by_end_pos[y.first];
      while (homophones.size() < translator_->max_homophones() &&
             !y.second.exhausted()) {
        homophones.push_back(y.second.Peek());
        if (!y.second.Next())
          break;
      }
    }
  }
}

WordGraph ScriptTranslation::PrepareForMakingSentence(
    Dictionary* dict,
    UserDictionary* user_dict) {
  const int kMaxSyllablesForUserPhraseQuery = 5;
  const auto& syllable_graph = syllabifier_->syllable_graph();
  WordGraph graph;
  for (const auto& x : syllable_graph.edges) {
    auto& same_start_pos = graph[x.first];
    if (user_dict) {
      EnrollEntries(same_start_pos,
                    user_dict->Lookup(syllable_graph, x.first,
                                      kMaxSyllablesForUserPhraseQuery));
    }
    // merge lookup results
    EnrollEntries(same_start_pos, dict->Lookup(syllable_graph, x.first,
                                               &translator_->blacklist()));
  }
  return graph;
}

deque<an<Sentence>> ScriptTranslation::MakeSentences(
    Dictionary* dict,
    UserDictionary* user_dict) {
  const auto& syllable_graph = syllabifier_->syllable_graph();
  WordGraph graph = PrepareForMakingSentence(dict, user_dict);
  auto sentences =
      poet_->MakeSentences(graph, syllable_graph.interpreted_length,
                           translator_->GetPrecedingText(start_),
                           max_sentences_, sentence_cutoff_threshold_);
  for (auto& sentence : sentences) {
    sentence->Offset(start_);
    sentence->set_syllabifier(syllabifier_);
  }
  return sentences;
}

an<Sentence> ScriptTranslation::MakeSentence(Dictionary* dict,
                                             UserDictionary* user_dict) {
  const auto& syllable_graph = syllabifier_->syllable_graph();
  WordGraph graph = PrepareForMakingSentence(dict, user_dict);
  if (auto sentence =
          poet_->MakeSentence(graph, syllable_graph.interpreted_length,
                              translator_->GetPrecedingText(start_))) {
    sentence->Offset(start_);
    sentence->set_syllabifier(syllabifier_);
    return sentence;
  }
  return nullptr;
}

}  // namespace rime
