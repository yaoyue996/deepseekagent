package com.deepseekv2.agent.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 轻量 Markdown 渲染：
 * 支持 ```代码块```、`行内代码`、**粗体**、*斜体*、~~删除线~~、标题与列表。
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseBlocks(markdown) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block)
                is MdBlock.Paragraph -> Text(
                    text = annotateInline(block.text),
                    style = MaterialTheme.typography.bodyLarge
                )
                is MdBlock.Heading -> Text(
                    text = annotateInline(stripLeadingHashes(block.text)),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp)
                )
                is MdBlock.Bullet -> Row {
                    Text(
                        "•  ",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = annotateInline(block.text),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}

private sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Code(val code: String, val language: String) : MdBlock
    data class Heading(val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
}

private fun parseBlocks(md: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    var inCode = false
    val codeBuf = StringBuilder()
    var codeLang = ""
    val paraBuf = StringBuilder()

    fun flushPara() {
        if (paraBuf.isNotBlank()) {
            for (line in paraBuf.toString().split('\n')) {
                val t = line.trim()
                when {
                    t.isEmpty() -> {}
                    t.startsWith("#") -> blocks.add(MdBlock.Heading(t))
                    t.startsWith("- ") || t.startsWith("* ") || t.startsWith("+ ") ->
                        blocks.add(MdBlock.Bullet(t.substring(2).trim()))
                    Regex("^\\d+\\.\\s").containsMatchIn(t) ->
                        blocks.add(MdBlock.Bullet(t.substringAfter('.').trim()))
                    else -> blocks.add(MdBlock.Paragraph(t))
                }
            }
        }
        paraBuf.clear()
    }

    for (line in md.split('\n')) {
        if (line.trimStart().startsWith("```")) {
            if (inCode) {
                blocks.add(MdBlock.Code(codeBuf.toString(), codeLang))
                codeBuf.clear(); codeLang = ""; inCode = false
            } else {
                flushPara()
                inCode = true
                codeLang = line.trim().removePrefix("```").trim()
            }
        } else if (inCode) {
            codeBuf.appendLine(line)
        } else {
            paraBuf.appendLine(line)
        }
    }
    if (inCode && codeBuf.isNotEmpty()) blocks.add(MdBlock.Code(codeBuf.toString(), codeLang))
    flushPara()
    return blocks
}

private fun stripLeadingHashes(s: String): String = s.trimStart().dropWhile { it == '#' }.trim()

private val inlineRegex =
    Regex("(\\*\\*[^*\\n]+\\*\\*)|(\\*[^*\\n]+\\*)|(`[^`\\n]+`)|(~~[^~\\n]+~~)")

@Composable
private fun annotateInline(text: String): AnnotatedString {
    val codeBg = if (MaterialTheme.colorScheme.background.luminance() > 0.5f)
        Color(0x14000000) else Color(0x22FFFFFF)
    return buildAnnotatedString {
        var last = 0
        for (m in inlineRegex.findAll(text)) {
            append(text.substring(last, m.range.first))
            val token = m.value
            when {
                token.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(token.drop(2).dropLast(2))
                }
                token.startsWith("~~") -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    append(token.drop(2).dropLast(2))
                }
                token.startsWith("`") -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, background = codeBg)
                ) { append(token.drop(1).dropLast(1)) }
                else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(token.drop(1).dropLast(1))
                }
            }
            last = m.range.last + 1
        }
        append(text.substring(last))
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (block.language.isNotBlank()) {
                Text(
                    block.language,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }
            Text(
                text = block.code.trimEnd('\n'),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            )
        }
    }
}

/** 三点打字动画 */
@Composable
fun TypingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "dots")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = alpha),
                        RoundedCornerShape(50)
                    )
            )
        }
    }
}
