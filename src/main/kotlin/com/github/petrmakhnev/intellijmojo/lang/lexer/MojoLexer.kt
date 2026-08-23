package com.github.petrmakhnev.intellijmojo.lang.lexer

import com.github.petrmakhnev.intellijmojo.lang.psi.MojoTypes
import com.intellij.lexer.LexerBase
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType

/** Lexer without zero-width indentation tokens. */
open class MojoLexer protected constructor(
    private val emitLayoutTokens: Boolean,
) : LexerBase() {
    constructor() : this(false)

    private data class LexedToken(
        val type: IElementType,
        val start: Int,
        val end: Int,
    )

    private var buffer: CharSequence = ""
    private var bufferEnd = 0
    private var requestedStart = 0
    private var tokens: List<LexedToken> = emptyList()
    private var tokenIndex = 0

    override fun start(
        buffer: CharSequence,
        startOffset: Int,
        endOffset: Int,
        initialState: Int,
    ) {
        this.buffer = buffer
        this.bufferEnd = endOffset
        requestedStart = startOffset
        tokens = tokenize(buffer, endOffset)
        val firstTokenIndex = tokens.indexOfFirst { token ->
            if (token.start == token.end) token.start >= startOffset else token.end > startOffset
        }.let { if (it < 0) tokens.size else it }
        tokenIndex = if (emitLayoutTokens && initialState > 0) {
            findSyntheticToken(startOffset, initialState) ?: firstTokenIndex
        } else {
            firstTokenIndex
        }
    }

    /**
     * IntelliJ requires advance() to change either the range or the lexer
     * state. Synthetic indentation tokens have an empty range, so their
     * 1-based ordinal at the current offset is exposed as the restart state.
     */
    override fun getState(): Int = if (emitLayoutTokens) syntheticOrdinal(tokenIndex) else 0

    override fun getTokenType(): IElementType? = tokens.getOrNull(tokenIndex)?.type

    override fun getTokenStart(): Int {
        val token = tokens.getOrNull(tokenIndex) ?: return bufferEnd
        return if (token.end > token.start) maxOf(token.start, requestedStart) else token.start
    }

    override fun getTokenEnd(): Int = tokens.getOrNull(tokenIndex)?.end ?: bufferEnd

    override fun advance() {
        if (tokenIndex < tokens.size) tokenIndex++
        requestedStart = 0
    }

    override fun getBufferSequence(): CharSequence = buffer

    override fun getBufferEnd(): Int = bufferEnd

    private fun findSyntheticToken(offset: Int, state: Int): Int? {
        var ordinal = 0
        for (index in tokens.indices) {
            val token = tokens[index]
            if (token.start != offset || token.end != offset) continue
            ordinal++
            if (ordinal == state) return index
        }
        return null
    }

    private fun syntheticOrdinal(index: Int): Int {
        val current = tokens.getOrNull(index) ?: return 0
        if (current.start != current.end) return 0

        var ordinal = 1
        var previousIndex = index - 1
        while (previousIndex >= 0) {
            val previous = tokens[previousIndex]
            if (previous.start != current.start || previous.end != current.end) break
            ordinal++
            previousIndex--
        }
        return ordinal
    }

    private fun tokenize(text: CharSequence, endOffset: Int): List<LexedToken> {
        val result = ArrayList<LexedToken>()
        val indentStack = ArrayList<Int>()
        indentStack += 0

        var offset = 0
        var bracketDepth = 0
        var physicalLineStart = true
        var indentationSensitive = true
        var logicalLineHasCode = false

        fun emit(type: IElementType, start: Int, end: Int) {
            result += LexedToken(type, start, end)
        }

        fun closeIndents(indent: Int, position: Int) {
            while (indentStack.size > 1 && indent < indentStack.last()) {
                indentStack.removeAt(indentStack.lastIndex)
                emit(MojoTypes.DEDENT, position, position)
            }
            if (indent != indentStack.last()) {
                emit(MojoTypes.INCONSISTENT_DEDENT, position, position)
            }
        }

        while (offset < endOffset) {
            if (physicalLineStart) {
                val whitespaceStart = offset
                var indent = 0
                while (offset < endOffset) {
                    when (text[offset]) {
                        ' ' -> {
                            indent++
                            offset++
                        }

                        '\t' -> {
                            indent = ((indent / TAB_WIDTH) + 1) * TAB_WIDTH
                            offset++
                        }

                        '\u000C' -> {
                            indent = 0
                            offset++
                        }

                        else -> break
                    }
                }
                if (offset > whitespaceStart) {
                    emit(TokenType.WHITE_SPACE, whitespaceStart, offset)
                }

                if (!emitLayoutTokens) {
                    physicalLineStart = false
                    continue
                }

                if (!indentationSensitive) {
                    physicalLineStart = false
                    continue
                }

                val next = text.getOrNull(offset)
                if (next == null) break
                if (next == '\n' || next == '\r' || next == '#') {
                    physicalLineStart = false
                    continue
                }

                val previousIndent = indentStack.last()
                when {
                    indent > previousIndent -> {
                        indentStack += indent
                        emit(MojoTypes.INDENT, offset, offset)
                    }

                    indent < previousIndent -> closeIndents(indent, offset)
                }
                physicalLineStart = false
            }

            if (offset >= endOffset) break
            val char = text[offset]

            if (char == ' ' || char == '\t' || char == '\u000C') {
                val start = offset
                while (offset < endOffset && (text[offset] == ' ' || text[offset] == '\t' || text[offset] == '\u000C')) {
                    offset++
                }
                emit(TokenType.WHITE_SPACE, start, offset)
                continue
            }

            if (char == '#') {
                val start = offset
                while (offset < endOffset && text[offset] != '\n' && text[offset] != '\r') offset++
                emit(MojoTypes.COMMENT, start, offset)
                continue
            }

            if (char == '\n' || char == '\r') {
                val start = offset
                offset = consumeLineBreak(text, offset, endOffset)
                if (emitLayoutTokens && bracketDepth == 0 && logicalLineHasCode) {
                    emit(MojoTypes.STATEMENT_BREAK, start, start)
                    logicalLineHasCode = false
                }
                emit(MojoTypes.LINE_BREAK, start, offset)
                physicalLineStart = true
                indentationSensitive = bracketDepth == 0
                continue
            }

            if (char == '\\' && isLineContinuation(text, offset, endOffset)) {
                val start = offset
                offset++
                while (offset < endOffset && (text[offset] == ' ' || text[offset] == '\t')) offset++
                offset = consumeLineBreak(text, offset, endOffset)
                emit(TokenType.WHITE_SPACE, start, offset)
                physicalLineStart = true
                indentationSensitive = false
                continue
            }

            val stringPrefix = findStringPrefix(text, offset, endOffset)
            if (stringPrefix != null) {
                val end = scanString(text, offset, stringPrefix.quoteOffset, endOffset)
                emit(
                    if (stringPrefix.isTemplate) MojoTypes.TEMPLATE_STRING_LITERAL else MojoTypes.STRING_LITERAL,
                    offset,
                    end,
                )
                offset = end
                logicalLineHasCode = true
                continue
            }

            if (char == '`') {
                val start = offset++
                while (offset < endOffset && text[offset] != '`' && text[offset] != '\n' && text[offset] != '\r') {
                    offset++
                }
                if (offset < endOffset && text[offset] == '`') offset++
                emit(MojoTypes.ESCAPED_IDENTIFIER, start, offset)
                logicalLineHasCode = true
                continue
            }

            if (isIdentifierStart(text, offset, endOffset)) {
                val start = offset
                offset = consumeIdentifier(text, offset, endOffset)
                val word = text.subSequence(start, offset).toString()
                val type = if (word == "_") {
                    MojoTypes.UNDERSCORE
                } else {
                    MojoTokenSets.keywordByText[word] ?: MojoTypes.IDENTIFIER
                }
                emit(type, start, offset)
                logicalLineHasCode = true
                continue
            }

            if (char.isDigit() || (char == '.' && text.getOrNull(offset + 1)?.isDigit() == true)) {
                val number = scanNumber(text, offset, endOffset)
                emit(if (number.isFloat) MojoTypes.FLOAT_LITERAL else MojoTypes.INTEGER_LITERAL, offset, number.end)
                offset = number.end
                logicalLineHasCode = true
                continue
            }

            val operator = MojoTokenSets.operatorByText.entries.firstOrNull { (spelling, _) ->
                matches(text, offset, endOffset, spelling)
            }
            if (operator != null) {
                val start = offset
                offset += operator.key.length
                emit(operator.value, start, offset)
                when (operator.value) {
                    MojoTypes.LPAR, MojoTypes.LBRACKET, MojoTypes.LBRACE -> bracketDepth++
                    MojoTypes.RPAR, MojoTypes.RBRACKET, MojoTypes.RBRACE -> {
                        if (bracketDepth > 0) bracketDepth--
                    }
                }
                logicalLineHasCode = true
                continue
            }

            emit(TokenType.BAD_CHARACTER, offset, offset + 1)
            offset++
            logicalLineHasCode = true
        }

        if (emitLayoutTokens) {
            if (logicalLineHasCode && bracketDepth == 0) {
                emit(MojoTypes.STATEMENT_BREAK, endOffset, endOffset)
            }
            while (indentStack.size > 1) {
                indentStack.removeAt(indentStack.lastIndex)
                emit(MojoTypes.DEDENT, endOffset, endOffset)
            }
        }
        return result
    }

    private data class StringPrefix(val quoteOffset: Int, val isTemplate: Boolean)

    private data class NumberScan(val end: Int, val isFloat: Boolean)

    private fun findStringPrefix(text: CharSequence, offset: Int, endOffset: Int): StringPrefix? {
        if (text[offset] == '\'' || text[offset] == '"') return StringPrefix(offset, false)
        var cursor = offset
        while (cursor < endOffset && cursor - offset < MAX_STRING_PREFIX && isStringPrefixChar(text[cursor])) cursor++
        if (cursor == offset || cursor >= endOffset || (text[cursor] != '\'' && text[cursor] != '"')) return null
        val prefix = text.subSequence(offset, cursor).toString().lowercase()
        if (prefix.any { it !in STRING_PREFIX_CHARS }) return null
        return StringPrefix(cursor, 'f' in prefix || 't' in prefix)
    }

    private fun scanString(text: CharSequence, start: Int, quoteOffset: Int, endOffset: Int): Int {
        val quote = text[quoteOffset]
        val triple = quoteOffset + 2 < endOffset && text[quoteOffset + 1] == quote && text[quoteOffset + 2] == quote
        var cursor = quoteOffset + if (triple) 3 else 1
        while (cursor < endOffset) {
            if (text[cursor] == '\\') {
                cursor += if (cursor + 1 < endOffset) 2 else 1
                continue
            }
            if (text[cursor] == quote) {
                if (triple) {
                    if (cursor + 2 < endOffset && text[cursor + 1] == quote && text[cursor + 2] == quote) {
                        return cursor + 3
                    }
                    cursor++
                    continue
                }
                return cursor + 1
            }
            if (!triple && (text[cursor] == '\n' || text[cursor] == '\r')) return cursor
            cursor++
        }
        return maxOf(cursor, start + 1)
    }

    private fun scanNumber(text: CharSequence, offset: Int, endOffset: Int): NumberScan {
        var cursor = offset
        var isFloat = false

        if (text[cursor] == '.') {
            isFloat = true
            cursor++
            cursor = consumeDigits(text, cursor, endOffset) { it.isDigit() }
        } else if (cursor + 1 < endOffset && text[cursor] == '0') {
            val prefix = text[cursor + 1]
            val predicate: ((Char) -> Boolean)? = when (prefix) {
                'x', 'X' -> { c -> c.isDigit() || c.lowercaseChar() in 'a'..'f' }
                'o', 'O' -> { c -> c in '0'..'7' }
                'b', 'B' -> { c -> c == '0' || c == '1' }
                else -> null
            }
            if (predicate != null) {
                cursor += 2
                cursor = consumeDigits(text, cursor, endOffset, predicate)
                if (cursor < endOffset && text[cursor] in NUMBER_SUFFIXES) cursor++
                return NumberScan(cursor, false)
            }
        }

        if (!isFloat) cursor = consumeDigits(text, cursor, endOffset) { it.isDigit() }
        if (cursor < endOffset && text[cursor] == '.' && !matches(text, cursor, endOffset, "...")) {
            isFloat = true
            cursor++
            cursor = consumeDigits(text, cursor, endOffset) { it.isDigit() }
        }
        if (cursor < endOffset && (text[cursor] == 'e' || text[cursor] == 'E')) {
            val exponentStart = cursor
            cursor++
            if (cursor < endOffset && (text[cursor] == '+' || text[cursor] == '-')) cursor++
            val digitsStart = cursor
            cursor = consumeDigits(text, cursor, endOffset) { it.isDigit() }
            if (cursor == digitsStart) cursor = exponentStart else isFloat = true
        }
        if (cursor < endOffset && text[cursor] in NUMBER_SUFFIXES) cursor++
        return NumberScan(cursor, isFloat)
    }

    private fun consumeDigits(
        text: CharSequence,
        offset: Int,
        endOffset: Int,
        predicate: (Char) -> Boolean,
    ): Int {
        var cursor = offset
        while (cursor < endOffset && (predicate(text[cursor]) || text[cursor] == '_')) cursor++
        return cursor
    }

    private fun consumeIdentifier(text: CharSequence, offset: Int, endOffset: Int): Int {
        var cursor = offset
        while (cursor < endOffset) {
            val codePoint = Character.codePointAt(text, cursor)
            if (cursor == offset) {
                if (codePoint != '_'.code && !Character.isUnicodeIdentifierStart(codePoint)) break
            } else if (codePoint != '_'.code && !Character.isUnicodeIdentifierPart(codePoint)) {
                break
            }
            cursor += Character.charCount(codePoint)
        }
        return cursor
    }

    private fun isIdentifierStart(text: CharSequence, offset: Int, endOffset: Int): Boolean {
        if (offset >= endOffset) return false
        val codePoint = Character.codePointAt(text, offset)
        return codePoint == '_'.code || Character.isUnicodeIdentifierStart(codePoint)
    }

    private fun consumeLineBreak(text: CharSequence, offset: Int, endOffset: Int): Int {
        return if (text[offset] == '\r' && offset + 1 < endOffset && text[offset + 1] == '\n') offset + 2 else offset + 1
    }

    private fun isLineContinuation(text: CharSequence, offset: Int, endOffset: Int): Boolean {
        var cursor = offset + 1
        while (cursor < endOffset && (text[cursor] == ' ' || text[cursor] == '\t')) cursor++
        return cursor < endOffset && (text[cursor] == '\n' || text[cursor] == '\r')
    }

    private fun matches(text: CharSequence, offset: Int, endOffset: Int, value: String): Boolean {
        if (offset + value.length > endOffset) return false
        for (index in value.indices) {
            if (text[offset + index] != value[index]) return false
        }
        return true
    }

    private fun isStringPrefixChar(char: Char): Boolean = char.lowercaseChar() in STRING_PREFIX_CHARS

    companion object {
        private const val TAB_WIDTH = 8
        private const val MAX_STRING_PREFIX = 6
        private const val STRING_PREFIX_CHARS = "rubftc"
        private val NUMBER_SUFFIXES = setOf('l', 'L', 'j', 'J', 'u', 'U')
    }
}

/** Parser lexer with logical-line and indentation tokens. */
class MojoIndentingLexer : MojoLexer(true)
