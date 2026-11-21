//
// Copyright RIME Developers
// Distributed under the BSD License
//
// 2012-01-17 GONG Chen <chen.sst@gmail.com>
//
// WhaleIME: 在 upstream 1.17 的 SpellingProperties 上补一个运行时字段
// correction_distance（纠错边的「误按键数」）。字段不参与 prism 序列化
// （SpellingDescriptor 里没有它），只在音节图的边上带着，供候选排序按
// 「改了几个键」而不是「改写了几个字节」扣分，见
// scripts/rime-script-translator-whale.cc 的 RankKey 说明。
//

#ifndef RIME_SPELLING_H_
#define RIME_SPELLING_H_

#include <rime/common.h>

namespace rime {

enum SpellingType {
  kNormalSpelling,
  kFuzzySpelling,
  kAbbreviation,
  kCompletion,
  kAmbiguousSpelling,
  kInvalidSpelling
};

struct SpellingProperties {
  SpellingType type = kNormalSpelling;
  size_t end_pos = 0;
  double credibility = 0.0;
  string tips;
  bool is_correction = false;
  // WhaleIME: 这条纠错边的编辑距离（0 表示原样解释）。一次误按改写的字节数取决于
  // 该音节的拼写长度（yiu→you 只错一个键，整个 3 字节音节都算纠错），所以候选排序
  // 按误按次数扣分，不能按被改写的字节数扣分。
  size_t correction_distance = 0;

  // 疊加運算規則, 將運算規則產生的屬性應用到當前拼寫上
  void Compose(const SpellingProperties& delta);
  // 更新優化拼寫, 將另一個候選拼寫合併到當前記錄
  void Update(const SpellingProperties& other);
};

struct Spelling {
  string str;
  SpellingProperties properties;

  Spelling() = default;
  Spelling(const string& _str) : str(_str) {}

  bool operator==(const Spelling& other) { return str == other.str; }
  bool operator<(const Spelling& other) { return str < other.str; }
};

}  // namespace rime

#endif  // RIME_SPELLING_H_
