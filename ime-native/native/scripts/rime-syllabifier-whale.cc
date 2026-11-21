//
// Copyright RIME Developers
// Distributed under the BSD License
//
// 2011-07-12 Zou Xu <zouivex@gmail.com>
// 2012-02-11 GONG Chen <chen.sst@gmail.com>
//
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <queue>
#include <boost/range/adaptor/reversed.hpp>
#include <rime/algo/syllabifier.h>
#include <rime/dict/corrector.h>
#include <rime/dict/prism.h>
#include "syllabifier.h"

namespace rime {
using namespace corrector;

// WhaleIME: 纠错性能量测开关（`CORR_STATS=1` 启动时，每次组音节图往 stderr 打一行）。
// 默认关闭，关掉时只有一个 static bool 的读取，不进热路径。
static bool CorrStatsEnabled() {
  static const bool enabled = ::getenv("CORR_STATS") != nullptr;
  return enabled;
}
static double CorrNowMs() {
  return std::chrono::duration<double, std::milli>(
             std::chrono::steady_clock::now().time_since_epoch())
      .count();
}
// 每段（同 consumed 长度）最多带几条纠错音节进音节图（见 BuildSyllableGraph 里的说明）。
// `CORR_MAX_SYLLABLES=0` 关闭限流，便于用同一份二进制做 A/B 量测。
static size_t CorrMaxSyllablesPerSegment() {
  static const size_t limit = [] {
    const char* env = ::getenv("CORR_MAX_SYLLABLES");
    if (!env)
      return (size_t)6;
    int v = ::atoi(env);
    return v > 0 ? (size_t)v : (size_t)0;
  }();
  return limit;
}

// WhaleIME 2026-11-19: 每段里**每个误按距离档**（错 1 键 / 错 2 键）各留几条。
//
// 旧实现只有「每段共 6 条」这一个上限，超出时按 (距离, 跨度, 音节 id) 截断 ——
// 距离档内的最后一道 tie-break 是**音节 id**，与「这个音节能不能组出词」毫无关系。
// lsishi 的实测：l 段上错 1 键的 lai/lei/li/si 占掉 4 个名额，剩下 2 个给了
// kai / kei（id 比 lao 小），**lao 被整条丢掉** —— 老师这条唯一需要 lao 的路径
// 就此消失，只能在「l 的单字母预测词」里排到第 91 位。
// 分档之后每个距离档都有保底名额：错 1 键的照旧先出，错 2 键的不再被 id 挤光。
// 取 4 与总上限 6 配合（错 1 键 4 条 + 错 2 键 2 条）：既不改变音节图的最大规模
// （组句耗时由纠错音节数量决定），又能让 hoafe 的 hao、lsishi 的 lao 留在图上。
constexpr size_t kMaxCorrectionsPerDistance = 4;

using Vertex = pair<size_t, SpellingType>;
using VertexQueue =
    std::priority_queue<Vertex, vector<Vertex>, std::greater<Vertex>>;

// 權重階梯：
// 1. 全拼：用戶完整輸入了所有編碼。Penalty = 0
// 2. 簡拼：用戶輸入了縮寫，明確希望匹配某個字。Penalty ≈ -2.3
// 3. 補全 (Completion)：用戶還沒打完，算法瞎猜的。Penalty ≈ -3.0
const double kCompletionPenalty = -2.995732273553991;      // log(0.05)
const double kCorrectionCredibility = -4.605170185988091;  // log(0.01)

// WhaleIME: 上游把纠错容错阈值硬编码为 5，配合 BFS 在整段剩余输入上替换，
// 3 个字母的乱码尾（"ndj"→"mei"）也能匹配，垃圾候选泛滥。
// 移动端误按模型：每个音节最多错 1~2 个键，阈值取 2。
const size_t kCorrectionTolerance = 2;

// WhaleIME: 同一段（[current_pos, end_pos)）上的同一个音节会被多条拼写命中，
// 最典型的一对是「简拼」与「纠错」：
//   ge 的简拼拼写 "g"（type=kAbbreviation，credibility≈-0.69）
//   g 漏打 e 时 corrector 的插入纠错 "g"→"ge"（is_correction，credibility≈-4.6）
// 上游合并时只 `type = min(type)`，credibility 与 is_correction 仍保留**先插入**
// 那条，于是纠错边被简拼边「洗白」成 type=Normal、is_correction=false 的精确边：
//   1) 躲过下面「已有更优类型的路径 → 裁掉简拼边」的裁剪（last_type 判定），
//   2) 又被 script_translator 当成「原样解释」参与桶内词频竞争（连 `?` 角标都不打）。
// beng → ben'g（本格）、bang → ban'g（半个）、lin → li'n（离你）都是这么冒出来的：
// 整串明明能被 beng / bang / lin 整音节解释，用户却先看到拆成两段的词。
// 合并时整条属性取「更优」的一份（非纠错 > 纠错，其次 type 小、credibility 高），
// 让简拼边保持 kAbbreviation 被裁掉、纠错边保持 is_correction 被打 `?` 并后排。
// 一个音节在同一段上只能留一份属性，所以「简拼」与「纠错」撞车时只能二选一：
// 选简拼（用户敲的那几个键本身就是这个音节的缩写，是更可能的意图），
// 代价是这一处不再同时充当纠错边 —— 该纠错若只此一条路径，候选会随之消失。
static bool PreferSpelling(const EdgeProperties& candidate,
                           const EdgeProperties& current) {
  if (candidate.is_correction != current.is_correction)
    return !candidate.is_correction;
  if (candidate.type != current.type)
    return candidate.type < current.type;
  return candidate.credibility > current.credibility;
}

// WhaleIME: 「简拼赢了合并」时被丢弃的那份纠错解释（见 PreferSpelling）。
// 简拼边在「整串能被完整拼写切完」时会被下面的裁剪整条删掉，那份纠错解释就跟着
// 一起消失 —— 本格 / 半个 / 离你 这类词会彻底看不到。裁剪之后把它补回图上（带
// is_correction + correction_distance），它们就以纠错身份参与竞争：cover 扣分、
// 打 `?`、排在精确候选之后、按词频排，而不是被抹掉。
struct DiscardedCorrection {
  size_t start;
  size_t end;
  SyllableId syllable_id;
  EdgeProperties props;
};

int Syllabifier::BuildSyllableGraph(const string& input,
                                    Prism& prism,
                                    SyllableGraph* graph) {
  if (input.empty())
    return 0;

  // WhaleIME: 量测脚手架（CORR_STATS=1 时输出，见文件头）
  const bool stats = CorrStatsEnabled();
  const double t_begin = stats ? CorrNowMs() : 0;
  double t_prefix = 0.0, t_corr = 0.0, t_expand = 0.0;
  size_t n_prefix = 0, n_corr_calls = 0, n_corr_syllables = 0;

  size_t farthest = 0;
  // WhaleIME: 精确匹配（非纠错）到达的最远位置。
  // 补全（Completion）只能从精确前沿生长：纠错边把 farthest 推到输入末尾时，
  // 上游会跳过补全分支，"nih" 的"你好"（ni + h→hao 补全）就再也出不来了。
  size_t farthest_exact = 0;
  // WhaleIME: 简拼/纠错撞车时被丢弃的纠错解释，裁剪之后再补回（见结构体注释）
  vector<DiscardedCorrection> discarded_corrections;
  // WhaleIME 2026-11-19: 被纠错边指出来的顶点位置。
  // 上游的顶点裁剪只认「拼写类型」（kNormalSpelling / kAbbreviation / …），而纠错
  // 边**不参与**类型计算（见下面 end_vertex_type 的 `!props.is_correction`）——
  // 于是「只靠纠错边才能到达的顶点」带着 kInvalidSpelling 落进裁剪，连同它的出边
  // 整条删掉。结果是纠错只能发生在**最后一个音节**（或精确前缀之后）：shgyici 的
  // 上一次（shang'yi'ci）要求第一个音节就是纠错（shg→shang），顶点 3 因此被删，
  // 整条路径组不出来，只剩「sh + g + yi + ci」这种全是缩写的解释。
  // 边这一层已经明确「Don't care correction edges」，顶点这一层必须同步豁免。
  set<size_t> correction_end_positions;
  VertexQueue queue;
  queue.push(Vertex{0, kNormalSpelling});  // start

  while (!queue.empty()) {
    Vertex vertex(queue.top());
    queue.pop();
    size_t current_pos = vertex.first;

    // record a visit to the vertex
    if (graph->vertices.find(current_pos) == graph->vertices.end()) {
      graph->vertices.insert(vertex);  // preferred spelling type comes first
    } else {
      //      graph->vertices[current_pos] =
      //          std::min(vertex.second, graph->vertices[current_pos]);
      continue;  // discard worse spelling types
    }

    if (current_pos > farthest)
      farthest = current_pos;

    // consume leading delimiters
    size_t begin_pos = current_pos;
    while (begin_pos < input.length() &&
           delimiters_.find(input[begin_pos]) != string::npos)
      ++begin_pos;
    DLOG(INFO) << "current_pos: " << current_pos
               << ", begin_pos: " << begin_pos;

    // see where we can go by advancing a syllable
    vector<Prism::Match> matches;
    // WhaleIME: 精确拼写的 (音节, 跨度)。旧实现只记音节 id，于是「同一音节在另一个
    // 跨度上靠纠错命中」也会被当成原样（is_correction 被漏标）。
    set<uint64_t> exact_spellings;
    // WhaleIME: (音节, 跨度) -> 纠错编辑距离（惩罚按距离缩放，替代上游的固定值）
    hash_map<uint64_t, size_t> correction_distances;
    auto current_input = input.substr(begin_pos);
    double t0 = stats ? CorrNowMs() : 0;
    prism.CommonPrefixSearch(current_input, &matches);
    if (stats) {
      t_prefix += CorrNowMs() - t0;
      ++n_prefix;
    }
    if (corrector_) {
      for (auto& m : matches) {
        exact_spellings.insert(Corrections::Key(m.value, m.length));
      }
      Corrections corrections;
      t0 = stats ? CorrNowMs() : 0;
      corrector_->ToleranceSearch(prism, current_input, &corrections,
                                  kCorrectionTolerance);
      if (stats) {
        t_corr += CorrNowMs() - t0;
        ++n_corr_calls;
        n_corr_syllables += corrections.size();
      }
      // WhaleIME 2026-11-10: 每段（同 consumed 长度）最多带 K 条纠错音节进音节图。
      //
      // 动机（量测见 scripts/query-console.cc 的 CORR_STATS）：
      //   `yiuxiwanjia` 的音节图只有 12 个顶点 / 38 条边，却挂了 700 条拼写，其中
      //   585 条是纠错音节。组句（Poet::MakeSentence 的 BeamSearch）按
      //   「7 条候选行 × 每条出边 × 每条边的词条」调 Grammar::Evaluate，
      //   而纠错音节对应的词条成片（每个音节几十条同音词），于是组句耗时随按键数
      //   平方增长：第 11 键 100 ms，11 键累计 350 ms —— 就是用户感觉到的卡。
      //   组音节图本身（含纠错 BFS）只占 1 ms，爆炸点在「纠错音节数量 → 组句路径数」。
      //
      // 取优次序：误按少优先 → 音节的字数和吃掉的按键数**越接近越好**
      // （同长度的换位/错键，优于删掉两个键才凑出来的音节，见 hoafe 的 hao
      // vs ba/ga：`hoa` 换位成 `hao` 只用掉一个换位，`ba`/`ga` 要删掉 h 和 o）
      // → 吃掉的按键多优先。同一段超出上限时丢弃尾部（不参与组句，也不出现
      // 在候选里）—— 所以「截断规则」直接决定哪些纠错能被组出来。
      vector<const Correction*> ranked;
      ranked.reserve(corrections.size());
      for (const auto& c : corrections)
        ranked.push_back(&c.second);
      std::stable_sort(
          ranked.begin(), ranked.end(),
          [](const Correction* a, const Correction* b) {
            if (a->distance != b->distance)
              return a->distance < b->distance;
            const size_t deviation_a =
                a->spelling_length > a->length ? a->spelling_length - a->length
                                               : a->length - a->spelling_length;
            const size_t deviation_b =
                b->spelling_length > b->length ? b->spelling_length - b->length
                                               : b->length - b->spelling_length;
            if (deviation_a != deviation_b)
              return deviation_a < deviation_b;
            if (a->length != b->length)
              return a->length > b->length;
            return a->syllable < b->syllable;
          });
      hash_map<size_t, size_t> taken_per_length;
      hash_map<uint64_t, size_t> taken_per_length_distance;
      // WhaleIME: 同一音节在同一距离档只保留一条跨度（取偏差最小、吃掉输入最多的
      // 那条，由上面的排序保证）。多跨度是「多音节纠错」能组出来的前提
      // （hoafe 的 hao 要 3 字节版本，cha 才不能独占首位），但一个音节带两条以上
      // 跨度只是同一件事的重复解释 —— 组句的路径数随边的数量指数增长，这里把它压住。
      set<uint64_t> spanned_syllables;
      const size_t max_corrections_per_segment = CorrMaxSyllablesPerSegment();
      for (const Correction* c : ranked) {
        size_t& taken = taken_per_length[c->length];
        if (max_corrections_per_segment &&
            taken >= max_corrections_per_segment)
          continue;
        size_t& taken_at_distance =
            taken_per_length_distance[Corrections::Key(c->length, c->distance)];
        if (taken_at_distance >= kMaxCorrectionsPerDistance)
          continue;
        if (!spanned_syllables
                 .insert(Corrections::Key(c->syllable, c->distance))
                 .second)
          continue;
        for (auto accessor = prism.QuerySpelling(c->syllable);
             !accessor.exhausted(); accessor.Next()) {
          auto props = accessor.properties();
          if (props.type == kNormalSpelling && !props.is_correction) {
            matches.push_back({c->syllable, c->length});
            correction_distances[Corrections::Key(c->syllable, c->length)] =
                c->distance;
            ++taken;
            ++taken_at_distance;
            break;
          }
        }
      }
    }

    size_t leading_gap = begin_pos - current_pos;
    if (!matches.empty()) {
      auto& end_vertices(graph->edges[current_pos]);
      for (const auto& m : matches) {
        if (m.length == 0)
          continue;
        size_t end_pos = current_pos + leading_gap + m.length;
        // consume trailing delimiters
        while (end_pos < input.length() &&
               delimiters_.find(input[end_pos]) != string::npos)
          ++end_pos;
        DLOG(INFO) << "end_pos: " << end_pos;
        bool matches_input = (current_pos == 0 && end_pos == input.length());
        SpellingMap& spellings(end_vertices[end_pos]);
        SpellingType end_vertex_type = kInvalidSpelling;
        // when spelling algebra is enabled,
        // a spelling evaluates to a set of syllables;
        // otherwise, it resembles exactly the syllable itself.
        SpellingAccessor accessor(prism.QuerySpelling(m.value));
        while (!accessor.exhausted()) {
          SyllableId syllable_id = accessor.syllable_id();
          EdgeProperties props(accessor.properties());
          if (strict_spelling_ && matches_input &&
              props.type != kNormalSpelling) {
            // disqualify fuzzy spelling or abbreviation as single word
          } else {
            props.end_pos = end_pos;
            // add a syllable with properties to the edge's
            // spelling-to-syllable map
            // WhaleIME: 这个 (音节, 跨度) 是不是靠改键命中的。按「音节 + 跨度」
            // 判定：同一音节在别的跨度上是精确拼写，不代表这一条边也是原样。
            const uint64_t spelling_key =
                Corrections::Key(m.value, m.length);
            if (corrector_ &&
                exact_spellings.find(spelling_key) == exact_spellings.end()) {
              props.is_correction = true;
              // WhaleIME: 每错一个键扣一次 log(0.01)。
              // 上游不区分改 1 个键还是 3 个键，"ndj"→"mei" 与手误一档。
              auto dist = correction_distances.find(spelling_key);
              const size_t distance =
                  dist == correction_distances.end() ? 1 : dist->second;
              // WhaleIME: 误按键数随边一起带出去 —— 候选排序按「改了几个键」扣分，
              // 不按「改写了几个字节」（见 script_translator 的 RankKey）。
              props.correction_distance = distance;
              props.credibility = kCorrectionCredibility * distance;
              correction_end_positions.insert(end_pos);
            }
            auto it = spellings.find(syllable_id);
            if (it == spellings.end()) {
              spellings.insert({syllable_id, props});
            } else if (PreferSpelling(props, it->second)) {
              // WhaleIME: 整条属性取更优的一份（上游只取 min(type)，会把
              // 纠错边洗成精确边，见 PreferSpelling 的说明）
              it->second = props;
            } else if (props.is_correction && !it->second.is_correction) {
              // WhaleIME: 简拼赢了，但纠错解释不丢 —— 先收着，等裁剪完再补回去
              discarded_corrections.push_back(
                  DiscardedCorrection{current_pos, end_pos, syllable_id, props});
            }
            // let end_vertex_type be the best (smaller) type of spelling
            // that ends at the vertex
            if (end_vertex_type > props.type && !props.is_correction) {
              end_vertex_type = props.type;
            }
          }
          accessor.Next();
        }
        if (spellings.empty()) {
          DLOG(INFO) << "not spelled.";
          end_vertices.erase(end_pos);
          continue;
        }
        // find the best common type in a path up to the end vertex
        // eg. pinyin "shurfa" has vertex type kNormalSpelling at position 3,
        // kAbbreviation at position 4 and kAbbreviation at position 6
        if (end_vertex_type < vertex.second) {
          end_vertex_type = vertex.second;
        }
        // WhaleIME: 仅当该终点存在非纠错拼写路径时才推进精确前沿
        if (end_vertex_type != kInvalidSpelling && end_pos > farthest_exact) {
          farthest_exact = end_pos;
        }
        queue.push(Vertex{end_pos, end_vertex_type});
        DLOG(INFO) << "added to syllable graph, edge: [" << current_pos << ", "
                   << end_pos << ")";
      }
    }
  }

  DLOG(INFO) << "remove stale vertices and edges";
  set<int> good;
  good.insert(farthest);
  // WhaleIME: 精确前沿是补全的生长点，且清理发生在补全之前——
  // 若不保护，通向它的边会因「不在 good 里」被清掉（新词库无 h→n 纠错边时
  // "nih" 的 ni 边即被误删），补全就永远挂不回来。把它并入 good 并跳过删除。
  if (farthest_exact < farthest) {
    good.insert((int)farthest_exact);
  }
  // fuzzy spellings are immune to invalidation by normal spellings
  SpellingType last_type =
      (std::max)(graph->vertices[farthest], kFuzzySpelling);
  for (int i = farthest - 1; i >= 0; --i) {
    if (graph->vertices.find(i) == graph->vertices.end())
      continue;
    if (i == (int)farthest_exact && farthest_exact < farthest) {
      // 保留精确前沿顶点（其出边由后面的补全补上）
      continue;
    }
    // remove stale edges
    for (auto j = graph->edges[i].begin(); j != graph->edges[i].end();) {
      if (good.find(j->first) == good.end()) {
        // not connected
        graph->edges[i].erase(j++);
        continue;
      }
      // remove disqualified syllables (eg. matching abbreviated spellings)
      // when there is a path of more favored type
      SpellingType edge_type = kInvalidSpelling;
      for (auto k = j->second.begin(); k != j->second.end();) {
        if (k->second.is_correction) {
          ++k;
          continue;  // Don't care correction edges
        }
        if (k->second.type > last_type) {
          j->second.erase(k++);
        } else {
          if (k->second.type < edge_type)
            edge_type = k->second.type;
          ++k;
        }
      }
      if (j->second.empty()) {
        graph->edges[i].erase(j++);
      } else {
        if (edge_type < kAbbreviation)
          CheckOverlappedSpellings(graph, i, j->first);
        ++j;
      }
    }
    // WhaleIME: 顶点类型「不够好」可以删 —— 但只有当它也不是纠错边的终点时。
    // 纠错边在上一层的裁剪里是被豁免的（Don't care correction edges），把它的
    // 终点按类型删掉，等于让「纠错」只能出现在末尾（见 correction_end_positions）。
    const bool correction_joint =
        correction_end_positions.find((size_t)i) !=
        correction_end_positions.end();
    if ((graph->vertices[i] > last_type && !correction_joint) ||
        graph->edges[i].empty()) {
      DLOG(INFO) << "remove stale vertex at " << i;
      graph->vertices.erase(i);
      graph->edges.erase(i);
      continue;
    }
    // keep the valid vertex
    good.insert(i);
  }

  // WhaleIME: 把「简拼赢下合并、随后随简拼边一起被裁掉」的纠错解释补回图上
  // （见 DiscardedCorrection）。只有简拼边真的被删掉时才补，所以
  // ① 纯简拼输入（无可裁的边）完全不受影响，简拼不会被打成纠错；
  // ② 「整串能被完整拼写切完」的输入（beng/bang/lin…）里，本格/半个/离你
  //    仍以纠错身份留着：cover 扣分、打 `?`、排在精确候选之后，按词频竞争。
  for (const auto& d : discarded_corrections) {
    if (d.end > farthest)
      continue;
    auto& end_vertices(graph->edges[d.start]);
    auto e = end_vertices.find(d.end);
    if (e != end_vertices.end() &&
        e->second.find(d.syllable_id) != e->second.end())
      continue;  // 简拼解释还在，不缺这份纠错
    end_vertices[d.end].insert({d.syllable_id, d.props});
    // 顶点可能随整条边一起被删；恢复它，否则 syllabify_dfs 走不到这条边
    graph->vertices.insert(Vertex{d.start, kAbbreviation});
    graph->vertices.insert(Vertex{d.end, kAbbreviation});
  }

  // WhaleIME: 补全从精确前沿生长（farthest_exact），而非 farthest——
  // 纠错边可达输入末尾时 farthest == input.length()，上游会整体跳过补全，
  // 真正想要的候选（"nih"→"你好"=ni+h→hao 补全）反而消失。
  // 补全与纠错边平行共存，靠 credibility（-3.0 vs -4.6×距离）公平竞争。
  if (enable_completion_ && farthest_exact < input.length()) {
    DLOG(INFO) << "completion enabled";
    const size_t kExpandSearchLimit = 512;
    vector<Prism::Match> keys;
    double t0 = stats ? CorrNowMs() : 0;
    prism.ExpandSearch(input.substr(farthest_exact), &keys,
                       kExpandSearchLimit);
    if (stats)
      t_expand += CorrNowMs() - t0;
    if (!keys.empty()) {
      size_t current_pos = farthest_exact;
      size_t end_pos = input.length();
      size_t code_length = end_pos - current_pos;
      // 顶点可能在清理 stale 顶点时被移除（无出边），补全会补回一条边，
      // 顶点本身也要恢复，否则 syllabify_dfs 可能走不到这条边
      graph->vertices.insert(Vertex{current_pos, kNormalSpelling});
      auto& end_vertices(graph->edges[current_pos]);
      auto& spellings(end_vertices[end_pos]);
      for (const auto& m : keys) {
        if (m.length < code_length)
          continue;
        // when spelling algebra is enabled,
        // a spelling evaluates to a set of syllables;
        // otherwise, it resembles exactly the syllable itself.
        SpellingAccessor accessor(prism.QuerySpelling(m.value));
        while (!accessor.exhausted()) {
          SyllableId syllable_id = accessor.syllable_id();
          SpellingProperties props = accessor.properties();
          if (props.type < kAbbreviation) {
            props.type = kCompletion;
            props.credibility += kCompletionPenalty;
            props.end_pos = end_pos;
            // add a syllable with properties to the edge's
            // spelling-to-syllable map
            spellings.insert({syllable_id, props});
          }
          accessor.Next();
        }
      }
      if (spellings.empty()) {
        DLOG(INFO) << "no completion could be made.";
        end_vertices.erase(end_pos);
      } else {
        DLOG(INFO) << "added to syllable graph, completion: [" << current_pos
                   << ", " << end_pos << ")";
        farthest = end_pos;
      }
    }
  }

  graph->input_length = input.length();
  graph->interpreted_length = farthest;
  DLOG(INFO) << "input length: " << graph->input_length;
  DLOG(INFO) << "syllabified length: " << graph->interpreted_length;

  if (stats) {
    size_t edges = 0, spellings = 0, corr_spellings = 0;
    for (const auto& start : graph->edges) {
      edges += start.second.size();
      for (const auto& end : start.second) {
        spellings += end.second.size();
        for (const auto& sp : end.second)
          if (sp.second.is_correction)
            ++corr_spellings;
      }
    }
    fprintf(stderr,
            "[corr] input=%zu farthest=%zu vertices=%zu edges=%zu "
            "spellings=%zu(corr %zu) prefix=%.2fms/%zu corr=%.2fms/%zu "
            "calls/%zu sylls expand=%.2fms total=%.2fms\n",
            input.length(), farthest, graph->vertices.size(), edges, spellings,
            corr_spellings, t_prefix, n_prefix, t_corr, n_corr_calls,
            n_corr_syllables, t_expand, CorrNowMs() - t_begin);
  }

  Transpose(graph);

  return farthest;
}

void Syllabifier::CheckOverlappedSpellings(SyllableGraph* graph,
                                           size_t start,
                                           size_t end) {
  if (!graph || graph->edges.find(start) == graph->edges.end())
    return;
  // if "Z" = "YX", mark the vertex between Y and X an ambiguous syllable joint
  auto& y_end_vertices(graph->edges[start]);
  // enumerate Ys
  for (const auto& y : y_end_vertices) {
    size_t joint = y.first;
    if (joint >= end)
      break;
    // test X
    if (graph->edges.find(joint) == graph->edges.end())
      continue;
    auto& x_end_vertices(graph->edges[joint]);
    for (auto& x : x_end_vertices) {
      if (x.first < end)
        continue;
      if (x.first == end) {
        // discourage syllables at an ambiguous joint
        // bad cases include pinyin syllabification "niju'ede"
        for (auto& spelling : x.second) {
          // 這條邊（X）相對於起點構成歧義
          spelling.second.ambiguous_source_positions.insert(start);
        }
        graph->vertices[joint] = kAmbiguousSpelling;
        DLOG(INFO) << "ambiguous syllable joint at position " << joint << ".";
      }
      break;
    }
  }
}

void Syllabifier::Transpose(SyllableGraph* graph) {
  for (const auto& start : graph->edges) {
    auto& index(graph->indices[start.first]);
    for (const auto& end : boost::adaptors::reverse(start.second)) {
      for (const auto& spelling : end.second) {
        SyllableId syll_id = spelling.first;
        index[syll_id].push_back(&spelling.second);
      }
    }
  }
}

void Syllabifier::EnableCorrection(Corrector* corrector) {
  corrector_ = corrector;
}

}  // namespace rime
