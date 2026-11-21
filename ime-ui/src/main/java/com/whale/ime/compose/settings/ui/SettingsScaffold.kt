package com.whale.ime.compose.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.whale.ime.ui.R

/**
 * 设置页统一骨架。
 *
 * - 折叠大标题（滚动时收成一行，`LargeTopAppBar`）；
 * - 内容固定是 `LazyColumn`（设置页几乎都是列表，避免各页自己拼 Column + verticalScroll）；
 * - 统一的左右留白、底部留白与 Snackbar 宿主。
 *
 * [bottomBar] 用于放常驻的底部操作条（如分页翻页），它不参与列表滚动，
 * 因此长列表也不需滚到底才能操作；底条自己负责导航栏 inset。
 *
 * [pinnedHeader] 用于放常驻在列表上方的头部（如键盘预览卡片）：它渲染在
 * `LazyColumn` 之外，永远完整可见。放在 `stickyHeader` 里的内容会被顶栏遮住
 * （吸顶位置是布局容器顶部，而不是顶栏下沿），且只占列表一项的高度。
 *
 * 页面若需要非列表布局（极少），直接用 `Scaffold` 自己拼。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    pinnedHeader: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back_24),
                                contentDescription = null,
                            )
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
    ) { innerPadding ->
        val layoutDirection = LocalLayoutDirection.current
        // 平板/折叠屏/横屏：内容限宽居中，避免每行被拉到 900dp 以上
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = SettingsDims.ContentMaxWidth)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    // 左右留白提到容器上，吸顶头部与列表两端对齐
                    .padding(
                        start = SettingsDims.PageHorizontal +
                                innerPadding.calculateLeftPadding(layoutDirection),
                        end = SettingsDims.PageHorizontal +
                                innerPadding.calculateRightPadding(layoutDirection),
                        top = innerPadding.calculateTopPadding(),
                    ),
            ) {
                pinnedHeader?.invoke()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(
                        top = SettingsDims.PageTop,
                        bottom = innerPadding.calculateBottomPadding() + SettingsDims.PageBottom,
                    ),
                    verticalArrangement = Arrangement.Top,
                    content = content,
                )
            }
        }
    }
}
