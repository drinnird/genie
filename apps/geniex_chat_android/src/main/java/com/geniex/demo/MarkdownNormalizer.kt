package com.geniex.demo

/**
 * Repairs a few common LLM Markdown mistakes before handing text to Markwon.
 * This is intentionally conservative: ordinary prose/code is returned unchanged.
 */
object MarkdownNormalizer {
    private val separatorToken = Regex(":?-{3,}:?")

    fun normalize(markdown: String): String {
        if (markdown.isEmpty()) return markdown
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        val codeLine = BooleanArray(lines.size)
        var fence: Char? = null
        for (i in lines.indices) {
            val trimmed = lines[i].trimStart()
            val marker = when {
                trimmed.startsWith("```") -> '`'
                trimmed.startsWith("~~~") -> '~'
                else -> null
            }
            if (fence != null) codeLine[i] = true
            if (marker != null) {
                codeLine[i] = true
                fence = if (fence == null) marker else if (fence == marker) null else fence
            }
        }

        // Models sometimes emit a literal trailing backslash after every table
        // row. It is unnecessary Markdown escaping and can prevent table parsing.
        for (i in lines.indices) {
            if (codeLine[i]) continue
            val trimmedStart = lines[i].trimStart()
            if (trimmedStart.startsWith("|")) {
                var row = lines[i].trimEnd()
                while (row.endsWith("\\")) row = row.dropLast(1).trimEnd()
                lines[i] = row
            }
        }

        for (i in 1 until lines.size) {
            if (codeLine[i] || codeLine[i - 1]) continue
            val headerCells = tableCells(lines[i - 1]) ?: continue
            if (headerCells.size < 2) continue
            val separatorCells = tableCells(lines[i]) ?: continue
            val flattened = separatorCells.flatMap { cell ->
                val pieces = cell.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                if (pieces.isNotEmpty() && pieces.all(separatorToken::matches)) pieces else listOf(cell.trim())
            }
            if (flattened.isEmpty() || !flattened.all(separatorToken::matches)) continue

            // A separator row defines the table width. If an LLM accidentally
            // joins adjacent ':---' cells with whitespace, split them. If one
            // separator is simply missing, pad it rather than dropping a column.
            val repaired = when {
                flattened.size == headerCells.size -> flattened
                flattened.size < headerCells.size -> flattened + List(headerCells.size - flattened.size) { "---" }
                else -> continue
            }
            lines[i] = "| ${repaired.joinToString(" | ")} |"
        }
        return lines.joinToString("\n")
    }

    private fun tableCells(line: String): List<String>? {
        val text = line.trim()
        if (!text.startsWith("|") || !text.contains('|')) return null
        val inner = text.removePrefix("|").removeSuffix("|")
        val cells = inner.split('|').map { it.trim() }
        return cells.takeIf { it.size >= 2 }
    }
}
