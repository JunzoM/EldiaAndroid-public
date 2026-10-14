package jp.eldia.hotel.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * タブの中の画面切り替え（一覧 → 詳細 → 入力など）を、横にすべる動きでつなぐ。
 * depth（階層の深さ）が深くなるときは右から入り、浅くなる（戻る）ときは左から入る。
 * 画面の中身は content に渡す route で描く（切り替え中は前後の画面が両方描かれるため）
 */
@Composable
fun RouteTransition(
    route: String,
    depth: Int,
    modifier: Modifier = Modifier,
    content: @Composable (String) -> Unit,
) {
    AnimatedContent(
        targetState = route to depth,
        modifier = modifier,
        transitionSpec = {
            val dir = if (targetState.second >= initialState.second) 1 else -1
            (slideInHorizontally { w -> dir * w / 4 } + fadeIn()) togetherWith
                (slideOutHorizontally { w -> -dir * w / 4 } + fadeOut())
        },
        contentKey = { it.first },
        label = "route",
    ) { (r, _) -> content(r) }
}
