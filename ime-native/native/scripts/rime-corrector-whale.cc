//
// Copyright RIME Developers
// Distributed under the BSD License
//
// Created by nameoverflow on 2018/11/14.
//

#include "corrector.h"
#include <algorithm>
#include <numeric>
#include <queue>
#include <rime/schema.h>
#include <rime/service.h>
#include <rime/ticket.h>

using namespace rime;
using namespace corrector;

// WhaleIME: 原版 keyboard_map 只含同行左右邻键，移动端拇指竖向误按（t↔f、a↔z 等）
// 完全覆盖不到。这里按 QWERTY 实际几何补上上下排斜向邻键（对齐 Kotlin 版
// SyllableTable.keyDistance 已验证的邻键模型），其余按键维持原样。
static hash_map<char, hash_set<char>> keyboard_map = {
    {'1', {'2', 'q', 'w'}},
    {'2', {'1', '3', 'q', 'w', 'e'}},
    {'3', {'2', '4', 'w', 'e', 'r'}},
    {'4', {'3', '5', 'e', 'r', 't'}},
    {'5', {'4', '6', 'r', 't', 'y'}},
    {'6', {'5', '7', 't', 'y', 'u'}},
    {'7', {'6', '8', 'y', 'u', 'i'}},
    {'8', {'7', '9', 'u', 'i', 'o'}},
    {'9', {'8', '0', 'i', 'o', 'p'}},
    {'0', {'9', '-', 'o', 'p', '['}},
    {'-', {'0', '=', 'p', '[', ']'}},
    {'=', {'-', '[', ']', '\\'}},
    {'q', {'w', 'a'}},
    {'w', {'q', 'e', 'a', 's'}},
    {'e', {'w', 'r', 's', 'd'}},
    {'r', {'e', 't', 'd', 'f'}},
    {'t', {'r', 'y', 'f', 'g'}},
    {'y', {'t', 'u', 'g', 'h'}},
    {'u', {'y', 'i', 'h', 'j'}},
    {'i', {'u', 'o', 'j', 'k'}},
    {'o', {'i', 'p', 'k', 'l'}},
    {'p', {'o', 'l'}},
    {'[', {'p', ']'}},
    {']', {'[', '\\'}},
    {'\\', {']'}},
    {'a', {'s', 'q', 'w', 'z', 'x'}},
    {'s', {'a', 'd', 'w', 'e', 'z', 'x'}},
    {'d', {'s', 'f', 'e', 'r', 'x', 'c'}},
    {'f', {'d', 'g', 'r', 't', 'c', 'v'}},
    {'g', {'f', 'h', 't', 'y', 'v', 'b'}},
    {'h', {'g', 'j', 'y', 'u', 'b', 'n'}},
    {'j', {'h', 'k', 'u', 'i', 'n', 'm'}},
    {'k', {'j', 'l', 'i', 'o', 'm'}},
    {'l', {'k', 'o', 'p'}},
    {';', {'l', '\''}},
    {'\'', {';'}},
    {'z', {'x', 'a', 's'}},
    {'x', {'z', 'c', 's', 'd'}},
    {'c', {'x', 'v', 'd', 'f'}},
    {'v', {'c', 'b', 'f', 'g'}},
    {'b', {'v', 'n', 'g', 'h'}},
    {'n', {'b', 'm', 'h', 'j'}},
    {'m', {'n', 'j', 'k'}},
    {',', {'m', '.'}},
    {'.', {',', '/'}},
    {'/', {'.'}},

    // WhaleIME: 14 键（QW/ER/TY/UI/OP · AS/DF/GH/JK/L · ZX/CV/BN/M）的键码邻接。
    //
    // 14 键送进引擎的是「键位首字母大写」（QW→'Q'、ER→'E'…），与上面的 QWERTY
    // 小写表完全是两套字符；不补这一段，SubstCost 查不到就回退 4（> 阈值 2），
    // 「按错隔壁键」永远走不到替换纠错，只剩漏按/多按/换位可用。
    //
    // 同键的两个字母（q/w、e/r…）共用同一个键码，不需要也不应该进邻键表：
    // 它们由 schema 的 derive 段直接枚举成同一个拼写。
    // 大写字符目前只由九键/14 键产生，九键没有开 enable_correction，故对本表无影响。
    {'Q', {'E', 'A'}},
    {'E', {'Q', 'T', 'D'}},
    {'T', {'E', 'U', 'G'}},
    {'U', {'T', 'O', 'J'}},
    {'O', {'U', 'L'}},
    {'A', {'Q', 'D', 'Z'}},
    {'D', {'A', 'E', 'G', 'C'}},
    {'G', {'D', 'T', 'J', 'B'}},
    {'J', {'G', 'U', 'L', 'M'}},
    {'L', {'J', 'O'}},
    {'Z', {'A', 'C'}},
    {'C', {'Z', 'D', 'B'}},
    {'B', {'C', 'G', 'M'}},
    {'M', {'B', 'J'}},
};

void DFSCollect(const string& origin,
                const string& current,
                size_t ed,
                Script& result);

Script SymDeleteCollector::Collect(size_t edit_distance) {
  // TODO: specifically for 1 length str
  Script script;

  for (auto& v : syllabary_) {
    DFSCollect(v, v, edit_distance, script);
  }

  return script;
}

void DFSCollect(const string& origin,
                const string& current,
                size_t ed,
                Script& result) {
  if (ed <= 0)
    return;
  for (size_t i = 0; i < current.size(); i++) {
    string temp = current;
    temp.erase(i, 1);
    Spelling spelling(origin);
    spelling.properties.tips = origin;
    result[temp].push_back(spelling);
    DFSCollect(origin, temp, ed - 1, result);
  }
}

void EditDistanceCorrector::ToleranceSearch(const Prism& prism,
                                            const string& key,
                                            Corrections* results,
                                            size_t threshold) {
  if (key.empty())
    return;
  size_t key_len = key.length();

  vector<size_t> jump_pos(key_len);

  auto match_next = [&](size_t& node, size_t& point) -> bool {
    auto res_val = trie_->traverse(key.c_str(), node, point, point + 1);
    if (res_val == -2)
      return false;
    if (res_val >= 0) {
      for (auto accessor = QuerySpelling(res_val); !accessor.exhausted();
           accessor.Next()) {
        auto origin = accessor.properties().tips;
        auto current_input = key.substr(0, point);
        if (origin == current_input) {
          continue;  // early termination: this comparison is O(n)
        }
        auto distance = RestrictedDistance(origin, current_input, threshold);
        if (distance <= threshold) {  // only trace near words
          SyllableId corrected;
          if (prism.GetValue(origin, &corrected)) {
            results->Alter(corrected, {distance, corrected, point});
          }
        }
      }
    }
    return true;
  };

  // pass through origin key, cache trie nodes
  size_t max_match = 0;
  for (size_t next_node = 0; max_match < key_len;) {
    jump_pos[max_match] = next_node;
    if (!match_next(next_node, max_match))
      break;
  }

  // start at the next position of deleted char
  for (size_t del_pos = 0; del_pos <= max_match; del_pos++) {
    size_t next_node = jump_pos[del_pos];
    for (size_t key_point = del_pos + 1; key_point < key_len;) {
      if (!match_next(next_node, key_point))
        break;
    }
  }
}

inline uint8_t SubstCost(char left, char right) {
  if (left == right)
    return 0;
  if (keyboard_map[left].find(right) != keyboard_map[left].end()) {
    return 1;
  }
  return 4;
}

// This nice O(min(m, n)) implementation is from
// https://en.wikibooks.org/wiki/Algorithm_Implementation/Strings/Levenshtein_distance#C++
Distance EditDistanceCorrector::LevenshteinDistance(const std::string& s1,
                                                    const std::string& s2) {
  // To change the type this function manipulates and returns, change
  // the return type and the types of the two variables below.
  auto s1len = (size_t)s1.size();
  auto s2len = (size_t)s2.size();

  auto column_start = (decltype(s1len))1;

  auto column = new decltype(s1len)[s1len + 1];
  std::iota(column + column_start - 1, column + s1len + 1, column_start - 1);

  for (auto x = column_start; x <= s2len; x++) {
    column[0] = x;
    auto last_diagonal = x - column_start;
    for (auto y = column_start; y <= s1len; y++) {
      auto old_diagonal = column[y];
      auto possibilities = {column[y] + 1, column[y - 1] + 1,
                            last_diagonal + SubstCost(s1[y - 1], s2[x - 1])};

      column[y] = (std::min)(possibilities);
      last_diagonal = old_diagonal;
    }
  }
  auto result = column[s1len];
  delete[] column;
  return result;
}

// L's distance with transposition allowed
Distance EditDistanceCorrector::RestrictedDistance(const std::string& s1,
                                                   const std::string& s2,
                                                   Distance threshold) {
  auto len1 = s1.size(), len2 = s2.size();
  vector<size_t> d((len1 + 1) * (len2 + 1));

  auto index = [len1, len2](size_t i, size_t j) { return i * (len2 + 1) + j; };

  d[0] = 0;
  for (size_t i = 1; i <= len1; ++i)
    d[index(i, 0)] = i * 2;
  for (size_t i = 1; i <= len2; ++i)
    d[index(0, i)] = i * 2;

  for (size_t i = 1; i <= len1; ++i) {
    auto min_d = threshold + 1;
    for (size_t j = 1; j <= len2; ++j) {
      d[index(i, j)] = (std::min)(
          {d[index(i - 1, j)] + 2, d[index(i, j - 1)] + 2,
           d[index(i - 1, j - 1)] + SubstCost(s1[i - 1], s2[j - 1])});
      if (i > 1 && j > 1 && s1[i - 2] == s2[j - 1] && s1[i - 1] == s2[j - 2]) {
        d[index(i, j)] = (std::min)(d[index(i, j)], d[index(i - 2, j - 2)] + 2);
      }
      min_d = (std::min)(min_d, d[index(i, j)]);
    }
    // early termination: do not continue if too far
    if (min_d > threshold)
      return min_d;
  }
  return (uint8_t)d[index(len1, len2)];
}
bool EditDistanceCorrector::Build(const Syllabary& syllabary,
                                  const Script* script,
                                  uint32_t dict_file_checksum,
                                  uint32_t schema_file_checksum) {
  Syllabary correct_syllabary;
  if (script && !script->empty()) {
    for (auto& v : *script) {
      correct_syllabary.insert(v.first);
    }
  } else {
    correct_syllabary = syllabary;
  }

  SymDeleteCollector collector(correct_syllabary);
  auto correction_script = collector.Collect((size_t)1);

  return Prism::Build(syllabary, &correction_script, dict_file_checksum,
                      schema_file_checksum);
}
EditDistanceCorrector::EditDistanceCorrector(const path& file_path)
    : Prism(file_path) {}

void NearSearchCorrector::ToleranceSearch(const Prism& prism,
                                          const string& key,
                                          Corrections* results,
                                          size_t threshold) {
  if (key.empty())
    return;

  using record = struct {
    size_t node_pos;    // trie 当前节点
    size_t idx;         // 下一个待匹配的输入位置
    size_t distance;
    char ch;            // 在 idx 处实际参与匹配的字符（替换语义）
    size_t inserts;     // 本路径已经用掉的「插入」次数（见下面的插入限流）
    size_t insert_at;   // 最近一次插入所在的输入位置（限定连写漏字）
    size_t spelling_len;  // trie 已经吃掉的字母数（= 音节拼写长度）
  };

  // WhaleIME: 原实现只有"邻键替换"一种编辑操作，漏打/多打/相邻颠倒三类
  // 高频误按永远走不到（如 "zhonguo"→"zhong'guo" 需要在音节起点前插入字母，
  // "niha"→"nihao" 需要在输入末尾插入字母）。这里把 BFS 扩展为通用的
  // 键盘误按编辑自动机。代价按"误按次数"计（Levenshtein 式）：
  //   替换（邻键，代价 1）
  //   插入（漏打字母，代价 1；trie 多吃一个字母、输入不动，含输入末尾）
  //   删除（多打字母，代价 1；跳过一个输入字符、trie 不动）
  //   换位（相邻颠倒，代价 2；等价于两次替换，如 "chagne"→"change"）
  // threshold=2 时：任意两次"单键"误按的组合。惩罚按距离缩放
  // （kCorrectionCredibility × distance），漏打一档与按错一档同价。
  const size_t kInsertCost = 1;
  const size_t kDeleteCost = 1;
  const size_t kTransposeCost = 2;
  // WhaleIME: 一段连写漏字最多补几个字母（见下面「插入」分支的说明）。
  // 上限还可以由 threshold 再压（补两个字就吃满两档预算）。
  const size_t kMaxInsertRun = 2;
  static const char* kAlphabet = "abcdefghijklmnopqrstuvwxyz";
  const size_t key_len = key.size();
  // 本地可写缓冲，末尾补一位哨兵：让"在输入末尾之后再插入一个字母"
  // （漏打尾字母）也能走 trie
  string buf = key;
  buf.push_back('\0');

  std::queue<record> queue;
  queue.push({0, 0, 0, key[0], 0, 0, 0});
  for (auto subst : keyboard_map[key[0]]) {
    queue.push({0, 0, 1, subst, 0, 0, 0});
  }

  auto record_result = [&](size_t distance, int val, size_t consumed,
                           size_t spelling_len) {
    if (val < 0)
      return;
    // WhaleIME 2026-11-19: 长度比例的合理性闸门。
    // 编辑距离只数「操作次数」，不区分「这个音节本来有多长」。删字/补字把
    // 比例拉开以后，「3 个按键被 1 个字母的音节吃掉」（hoa→a：删掉 h、o）
    // 和「3 个按键被 7 个字母的音节吃掉」（hoa→asakawa：细胞词库里的罗马字，
    // 补 4 个删 2 个）在距离上都是 2，于是一堆不可能的音节把名额占满、把
    // 真正只差一两个键的音节（hao）挤掉。
    // 真实误按不会让音节长度和按键数的比例离开 [1/2, 2]：漏一个字母最多
    // 让拼写比按键多 1（shg→shang 是 5:3），多打一个字母最多少 1。超出这个
    // 比例的「解释」不算误按，直接丢。
    const size_t kMaxLengthRatio = 2;
    if (spelling_len > consumed * kMaxLengthRatio ||
        consumed > spelling_len * kMaxLengthRatio)
      return;
    // WhaleIME: 同一音节在同一个起点上可能被多条编辑路径命中，而且**跨度不同**
    // （"wam" 里往 wa 后插一个 n 也能拼出 wan，只消耗 2 字节；m→n 邻键替换则
    // 消耗 3 字节）。这些是不同长度的边，必须各自保留 —— 只按音节去重会把
    // 「吃掉更多输入」的那条丢掉，see rime-corrector-whale.h。
    // 同一条跨度上只留误按最少的那份解释。
    const SyllableId syllable = (SyllableId)val;
    auto it = results->find(Corrections::Key(syllable, consumed));
    if (it != results->end() && distance >= it->second.distance)
      return;
    results->Alter(syllable, {distance, syllable, consumed, spelling_len});
  };

  for (; !queue.empty(); queue.pop()) {
    record rec = queue.front();  // 拷贝：traverse 会原地改写 node/idx
    size_t node = rec.node_pos;

    // ---- 插入：trie 多吃一个字母，输入不动（漏打字母）----
    // 把插入字母临时写到 buf[rec.idx] 让 trie 前进一步，但输入指针留在
    // rec.idx：终态的输入消耗量是 rec.idx，恢复匹配也从 rec.idx 继续。
    //
    // WhaleIME 2026-11-19: 允许**一段连写的漏字**（最多 kMaxInsertRun 个），
    // 但第二次以及之后的插入必须紧跟前一次（同一个 rec.idx），不能散落在音节
    // 各处：
    //   shgyici 的 shang：用户把 an 两个字母一起漏成了 "shg"，只在
    //   「sh 之后」这一个位置补字才是这件事；散开补字（sha 后补一个、再往别处
    //   补一个）只是把任何短音节硬凑成任意音节。
    // 2026-11-10 的限制是「一条路径最多插一个字母」，它的动机（枚举 2 字节输入
    // 的所有双插入 → 每顶点 180+ 条纠错音节 → 组句路径爆炸，yiuxi 646 ms）在
    // 「连写漏字」下不再成立：每个插入态只有 26 个分支（而不是 26×26），且音节
    // 越长、可用插入位置越少。上限仍由 threshold 兜住（两次插入吃满预算）。
    const bool insert_allowed =
        rec.distance + kInsertCost <= threshold &&
        (rec.inserts == 0 ||
         (rec.inserts < kMaxInsertRun && rec.insert_at == rec.idx));
    if (insert_allowed) {
      for (const char* pc = kAlphabet; *pc; ++pc) {
        size_t n = node, p = rec.idx;
        buf[rec.idx] = *pc;
        auto val = prism.trie().traverse(buf.c_str(), n, p, rec.idx + 1);
        if (val != -2) {
          record_result(rec.distance + kInsertCost, val, rec.idx,
                        rec.spelling_len + 1);
          queue.push({n, rec.idx, rec.distance + kInsertCost, key[rec.idx],
                      rec.inserts + 1, rec.idx, rec.spelling_len + 1});
        }
      }
      buf[rec.idx] = key[rec.idx];
    }

    // ---- 删除：跳过一个输入字符，trie 不动（多打字母）----
    if (rec.distance + kDeleteCost <= threshold) {
      if (rec.idx + 1 < key_len) {
        queue.push({node, rec.idx + 1, rec.distance + kDeleteCost,
                    key[rec.idx + 1], rec.inserts, rec.insert_at,
                    rec.spelling_len});
      } else if (rec.idx + 1 == key_len && rec.idx > 0) {
        // 删掉的是最后一个输入字符：音节即当前节点，用空区间查询终态
        size_t n = node, p = rec.idx;
        auto val = prism.trie().traverse(buf.c_str(), n, p, rec.idx);
        record_result(rec.distance + kDeleteCost, val, rec.idx,
                      rec.spelling_len);
      }
    }

    // ---- 换位：相邻两个输入字符颠倒 ----
    if (rec.distance + kTransposeCost <= threshold && rec.idx + 1 < key_len) {
      size_t n = node, p = rec.idx;
      std::swap(buf[rec.idx], buf[rec.idx + 1]);
      auto val = prism.trie().traverse(buf.c_str(), n, p, rec.idx + 2);
      std::swap(buf[rec.idx], buf[rec.idx + 1]);
      if (val != -2) {
        record_result(rec.distance + kTransposeCost, val, p,
                      rec.spelling_len + 2);
        if (p < key_len) {
          queue.push({n, p, rec.distance + kTransposeCost, key[p], rec.inserts,
                      rec.insert_at, rec.spelling_len + 2});
        }
      }
    }

    // ---- 主操作：消费 rec.ch（原样或替换后的邻键）----
    buf[rec.idx] = rec.ch;
    size_t idx = rec.idx;
    auto val = prism.trie().traverse(buf.c_str(), node, idx, rec.idx + 1);
    buf[rec.idx] = key[rec.idx];
    if (val == -2)
      continue;
    record_result(rec.distance, val, idx, rec.spelling_len + 1);
    if (idx < key_len) {
      queue.push({node, idx, rec.distance, key[idx], rec.inserts,
                  rec.insert_at, rec.spelling_len + 1});
      if (rec.distance < threshold) {
        for (auto subst : keyboard_map[key[idx]]) {
          queue.push({node, idx, rec.distance + 1, subst, rec.inserts,
                      rec.insert_at, rec.spelling_len + 1});
        }
      }
    } else if (rec.distance + kInsertCost <= threshold &&
               (rec.inserts == 0 ||
                (rec.inserts < kMaxInsertRun && rec.insert_at == key_len))) {
      // 输入已耗尽：漏打尾字母（"niha"→"nihao"），在哨兵位再插一个字母
      for (const char* pc = kAlphabet; *pc; ++pc) {
        size_t n = node, p = key_len;
        buf[key_len] = *pc;
        auto ival = prism.trie().traverse(buf.c_str(), n, p, key_len + 1);
        if (ival != -2) {
          // length 语义是"消耗的输入长度"：漏打尾字母时输入已全部耗尽，
          // 音节比输入长一个字符，消耗量仍是 key_len 而非哨兵位 p
          record_result(rec.distance + kInsertCost, ival, key_len,
                        rec.spelling_len + 1);
        }
      }
      buf[key_len] = '\0';
    }
  }
}
void CorrectorComponent::Unified::ToleranceSearch(const Prism& prism,
                                                  const string& key,
                                                  Corrections* results,
                                                  size_t tolerance) {
  for (auto& c : contents) {
    c->ToleranceSearch(prism, key, results, tolerance);
  }
}
CorrectorComponent::CorrectorComponent()
    : resolver_(Service::instance().CreateDeployedResourceResolver(
          {"corrector", "", ".correction.bin"})) {}

Corrector* CorrectorComponent::Create(const Ticket& ticket) noexcept {
  // Don't use edit distance based correction for now.
#if 0
  if (!ticket.schema) return nullptr;
  Config* config = ticket.schema->config();
  string prism_name;
  if (!config->GetString(ticket.name_space + "/prism", &prism_name)) {
    config->GetString(ticket.name_space + "/dictionary", &prism_name);
  }

  auto file_path = resolver_->ResolvePath(prism_name);

  auto ed_corrector = New<EditDistanceCorrector>(file_path);
  if (ed_corrector->Load()) {
    return Combine(New<NearSearchCorrector>(), ed_corrector);
  } else {
    return new NearSearchCorrector();
  }
#endif
  return new NearSearchCorrector();
}
