package com.deepseekv2.agent.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 开屏动画：DeepSeekAgent 字样逐字浮现 + 呼吸光晕 + 底部进度条。
 */
@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 1500, easing = FastOutSlowInEasing))
        delay(480)
        onFinished()
    }

    val p = progress.value

    // 呼吸光晕
    val glow = rememberInfiniteTransition(label = "glow")
    val glowAlpha by glow.animateFloat(
        initialValue = 0.14f,
        targetValue = 0.32f,
        animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
        label = "glowAlpha"
    )
    val glowScale by glow.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(2400), RepeatMode.Reverse),
        label = "glowScale"
    )

    val text = "DeepSeekAgent"
    val n = text.length

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF2F45C7), Color(0xFF4D6BFE), Color(0xFF6B83FF))
                )
            )
    ) {
        // 柔和光晕
        Box(
            Modifier
                .align(Alignment.Center)
                .size(300.dp)
                .graphicsLayer {
                    scaleX = glowScale
                    scaleY = glowScale
                    alpha = glowAlpha
                }
                .background(Color.White.copy(alpha = 0.6f), CircleShape)
                .blur(90.dp)
        )

        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo 弹出
            Text(
                text = "🐳",
                fontSize = 58.sp,
                modifier = Modifier.graphicsLayer {
                    val lp = ((p - 0.05f) / 0.25f).coerceIn(0f, 1f)
                    alpha = lp
                    scaleX = 0.4f + 0.6f * lp
                    scaleY = 0.4f + 0.6f * lp
                }
            )
            Spacer(Modifier.height(20.dp))

            // 逐字浮现
            Row(horizontalArrangement = Arrangement.Center) {
                text.forEachIndexed { i, ch ->
                    val start = (i / n.toFloat()) * 0.55f
                    val dur = 0.45f
                    val lp = ((p - start) / dur).coerceIn(0f, 1f)
                    val color = lerp(Color.White, Color(0xFFB8C6FF), i / (n - 1f).coerceAtLeast(1f))
                    Text(
                        text = ch.toString(),
                        color = color,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.graphicsLayer {
                            alpha = lp
                            translationY = (1f - lp) * 26f
                            scaleX = 0.7f + 0.3f * lp
                            scaleY = 0.7f + 0.3f * lp
                        }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // 副标题淡入
            val sub = ((p - 0.6f) / 0.35f).coerceIn(0f, 1f)
            Text(
                text = "智能 AI 助手",
                color = Color.White.copy(alpha = 0.85f * sub),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.graphicsLayer { alpha = sub }
            )
        }

        // 底部品牌 + 进度条
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "DeepSeekAgent V2",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
                letterSpacing = 1.sp
            )
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .size(width = 120.dp, height = 3.dp)
                    .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(2.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(p.coerceIn(0f, 1f))
                        .background(Color.White, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}
