package com.tvvpn.app.ui.theme

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

// TV 遥控器焦点效果——真机（M6 盒子）反馈：所有可点元素都用了 indication = null，
// D-pad 移过去完全看不出焦点在哪，一进页面也不知道当前选中的是什么。这里统一
// 提供一个"焦点高亮"modifier：焦点落上来时画一圈亮琥珀色粗边框 + 轻微放大，
// 所有可交互元素（首页三个入口、节点列表每一行、我的页三个按钮）都套这个，
// 视觉语言一致，老人在电视上离远了也能一眼看出"现在选中的是哪个"。
//
// 用法：跟 clickable(interactionSource = xxx) 共用同一个 MutableInteractionSource，
// 放在 clickable **之前**（外层），这样边框包住整个可点区域。

val FlintFocusBorder = FlintAmberBright
val FlintFocusSurface = Color(0xFF3A3328)

fun Modifier.tvFocusHighlight(
    interactionSource: MutableInteractionSource,
    shape: Shape,
    idleBorder: BorderStroke? = null,
    focusedScale: Float = 1.08f,
): Modifier = composed {
    val focused by interactionSource.collectIsFocusedAsState()
    val baseScale by animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        label = "tvFocusScale",
    )
    // "呼吸"效果（用户要求：选中的要有呼吸，一眼看出当前在哪）——只在焦点落上来时
    // 才跑无限动画：边框在琥珀色与亮琥珀色之间来回渐变、粗细 3dp↔5dp、整体再轻微
    // 胀缩。没焦点的元素不跑动画，节点列表几十行也不会白白耗电。
    val pulse: Float = if (focused) {
        val transition = rememberInfiniteTransition(label = "tvFocusBreath")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        ).value
    } else 0f
    val breathExtra = (focusedScale - 1f) * 0.5f
    val scale = if (focused) baseScale + pulse * breathExtra else baseScale
    val border = if (focused) {
        BorderStroke(
            3.dp + 2.dp * pulse,
            lerp(FlintAmber, FlintFocusBorder, pulse).copy(alpha = 0.75f + 0.25f * pulse),
        )
    } else idleBorder
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .then(if (border != null) Modifier.border(border, shape) else Modifier)
}

/**
 * 进页面时把焦点放到 [focusRequester] 上。M6 盒子真机踩到的坑：App 冷启动时窗口处于
 * 触摸模式（`dumpsys window` 里 mInTouchMode=true，TV 盒子也一样），这时候
 * requestFocus() 不会有任何可见效果，非得用户先按一下方向键退出触摸模式才行——
 * 所以先通过 Compose 的 InputModeManager 主动切到键盘模式（底层是
 * requestFocusFromTouch()，Android 唯一能编程退出触摸模式的口子），再请求焦点。
 * [key] 变化时重新执行（比如按钮要等数据到了才 enabled）。
 */
@Composable
fun RequestInitialTvFocus(focusRequester: FocusRequester, key: Any? = Unit, enabled: Boolean = true) {
    val inputModeManager = LocalInputModeManager.current
    // 第二个坑（同一台 M6 盒子）：冷启动时 LaunchedEffect 跑在窗口拿到焦点之前，这时
    // requestFocus() 静默无效，界面上还是什么都没选中——必须等 isWindowFocused 变 true
    // 再请求。只在拿到窗口焦点后做一次，之后用户自己用方向键走，不再抢焦点。
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var done by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key, enabled, windowFocused) {
        if (!enabled || !windowFocused || done) return@LaunchedEffect
        inputModeManager.requestInputMode(InputMode.Keyboard)
        if (runCatching { focusRequester.requestFocus() }.isSuccess) done = true
    }
}
