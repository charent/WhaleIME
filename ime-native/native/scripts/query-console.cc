// 离线查询台：在模拟器上直接跑 librime，复现/回归候选排序问题。
// 用法：每行一个拼音串（如 nih / fangq / landj），输出前 N 个候选 + comment；
//       空行 = 清空 Composer；#schema <id> = 切方案。
// 命令行：query_console [share] [user] [max]   —— max 默认 20；
//       排查「候选是不是排在很后面」时传大一点（如 80），配合误按语料使用。
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <rime_api.h>

// WhaleIME: 单次查询（按键序列 → 候选列表）的耗时，用来盯纠错引发的性能回归。
// 输出格式固定为 "time: 12.3 ms"，配合 qtime 脚本 grep 即可。
static double now_ms() {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

static void print_candidates(RimeApi* rime,
                             RimeSessionId sid,
                             const char* input,
                             int max) {
  RIME_STRUCT(RimeContext, ctx);
  if (!rime->get_context(sid, &ctx)) return;
  printf("input: %s | preedit: %s\n", input, ctx.composition.preedit);
  rime->free_context(&ctx);

  RimeCandidateListIterator it = {0};
  if (!rime->candidate_list_begin(sid, &it)) {
    printf("  (no candidates)\n\n");
    return;
  }
  int i = 0;
  while (i < max && rime->candidate_list_next(&it)) {
    printf("%d. %s%s%s%s\n", i + 1, it.candidate.text,
           it.candidate.comment ? "  [" : "",
           it.candidate.comment ? it.candidate.comment : "",
           it.candidate.comment ? "]" : "");
    ++i;
  }
  rime->candidate_list_end(&it);
  printf("\n");
}

int main(int argc, char** argv) {
  RimeApi* rime = rime_get_api();

  const char* share = argc > 1 ? argv[1] : "/data/local/tmp/rime-q/share";
  const char* user = argc > 2 ? argv[2] : "/data/local/tmp/rime-q/user";
  const int max_candidates = argc > 3 ? atoi(argv[3]) : 20;
  // 第 4 个参数 = prebuilt_data_dir（App 把它指到 rime-v*/build，直接用随包发布的
  // 部署产物；不给就在 user/build 里现编一份，1.88M 词条要跑很久）。
  const char* prebuilt = argc > 4 ? argv[4] : NULL;
  RIME_STRUCT(RimeTraits, traits);
  traits.app_name = "rime.query";
  traits.shared_data_dir = share;
  traits.user_data_dir = user;
  if (prebuilt && *prebuilt)
    traits.prebuilt_data_dir = prebuilt;
  traits.min_log_level = 0;  // 打开 INFO 日志，便于定位「为什么没候选」
  rime->setup(&traits);
  rime->initialize(NULL);
  // 给了 prebuilt 就和 App 一样「直接吃发布数据」，不再跑部署检查。
  // 一旦让 maintenance 认为需要部署，它会把 table.bin 重建到 user/build，
  // 而 prism 仍用 share/build 的那份 —— 两边 syllable id 排不上，
  // 查询时刷屏 "Error translating syllable_id" 且只剩回显候选（踩过）。
  if (!(prebuilt && *prebuilt) && rime->start_maintenance(False))
    rime->join_maintenance_thread();
  fprintf(stderr, "ready\n");

  RimeSessionId sid = rime->create_session();
  if (!sid) { fprintf(stderr, "no session\n"); return 1; }

  char line[256];
  while (fgets(line, sizeof(line), stdin)) {
    size_t n = strlen(line);
    while (n && (line[n-1] == '\n' || line[n-1] == '\r')) line[--n] = 0;
    if (n == 0) { rime->clear_composition(sid); continue; }
    // 取一页候选（和真实键盘刷新一次候选的量一致）。不翻全部：候选是懒加载的，
    // 全都翻出来会把翻译/组句的完整开销算进耗时，不代表手速体感。
    auto take_page = [&](RimeSessionId s) -> int {
      RimeCandidateListIterator it = {0};
      int count = 0;
      if (rime->candidate_list_begin(s, &it)) {
        while (count < max_candidates && rime->candidate_list_next(&it))
          ++count;
        rime->candidate_list_end(&it);
      }
      return count;
    };
    if (line[0] == '@') {
      // 逐键模式：每个按键都真的取一次候选（真实输入法每敲一个键就刷候选），
      // 只打印每键耗时，不打印候选。用于定位「哪一下开始变慢」。
      rime->clear_composition(sid);
      const char* keys = line + 1;
      double total = 0;
      for (size_t i = 0; keys[i]; ++i) {
        char k[2] = {keys[i], 0};
        double t = now_ms();
        rime->simulate_key_sequence(sid, k);
        int count = take_page(sid);
        double d = now_ms() - t;
        total += d;
        printf("  key %2u '%c' -> %3d cands  %.2f ms\n", (unsigned)i + 1,
               keys[i], count, d);
      }
      printf("time: %.1f ms (keys: %u, avg %.2f ms/key)\n\n", total,
             (unsigned)strlen(keys), total / (strlen(keys) ? strlen(keys) : 1));
      rime->clear_composition(sid);
      continue;
    }
    if (line[0] == '~') {
      // 删除模式：整串输入后逐键退格，每退一格取一次候选 ——
      // 复现「长按删除越删越卡」（每次删除都是一次完整重算）。
      rime->clear_composition(sid);
      const char* keys = line + 1;
      rime->simulate_key_sequence(sid, keys);
      const size_t len = strlen(keys);
      double total = 0;
      for (size_t i = len; i > 0; --i) {
        double t = now_ms();
        rime->simulate_key_sequence(sid, "{BackSpace}");
        int count = take_page(sid);
        double d = now_ms() - t;
        total += d;
        printf("  bs %2u -> %3d cands  %.2f ms\n", (unsigned)(len - i + 1),
               count, d);
      }
      printf("time: %.1f ms (backspaces: %u, avg %.2f ms/key)\n\n", total,
             (unsigned)len, total / (len ? len : 1));
      rime->clear_composition(sid);
      continue;
    }
    if (line[0] == '#') {
      if (strncmp(line, "#deploy", 7) == 0) {
        fprintf(stderr, "deploying...\n");
        if (rime->start_maintenance(True))
          rime->join_maintenance_thread();
        fprintf(stderr, "deploy done\n");
      } else if (strncmp(line, "#schema ", 8) == 0) {
        Bool ok = rime->select_schema(sid, line + 8);
        char cur[128] = {0};
        rime->get_current_schema(sid, cur, sizeof(cur));
        fprintf(stderr, "select_schema(%s) -> %d, current=%s\n", line + 8, ok,
                cur);
      } else if (strncmp(line, "#setinput ", 10) == 0) {
        // 分段选择依赖「整串替换输入」，这里用来确认 librime 在组合态下
        // set_input 是否真的生效（Kotlin 侧 RimeEngine.focusSegment /
        // commitFocusedSegment 走的同一条路）。
        Bool ok = rime->set_input(sid, line + 10);
        const char* cur = rime->get_input(sid);
        fprintf(stderr, "set_input(%s) -> %d, input=%s\n", line + 10, ok,
                cur ? cur : "(null)");
        double t0 = now_ms();
        print_candidates(rime, sid, line + 10, max_candidates);
        printf("time: %.1f ms\n\n", now_ms() - t0);
      }
      continue;
    }
    rime->clear_composition(sid);
    double t0 = now_ms();
    rime->simulate_key_sequence(sid, line);
    print_candidates(rime, sid, line, max_candidates);
    double ms = now_ms() - t0;
    printf("time: %.1f ms (keys: %u, avg %.2f ms/key)\n\n", ms,
           (unsigned)n, ms / (n ? n : 1));
    rime->clear_composition(sid);
  }
  return 0;
}
