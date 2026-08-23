package com.github.petrmakhnev.intellijmojo.ide.colors

import com.github.petrmakhnev.intellijmojo.MojoBundle
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.options.colors.AttributesDescriptor
import java.util.function.Supplier

enum class MojoColor(displayName: Supplier<String>, fallback: TextAttributesKey) {
    VARIABLE(MojoBundle.messagePointer("settings.mojo.color.variable"), DefaultLanguageHighlighterColors.LOCAL_VARIABLE),
    MUTABLE_VARIABLE(MojoBundle.messagePointer("settings.mojo.color.variable.mutable"), DefaultLanguageHighlighterColors.REASSIGNED_LOCAL_VARIABLE),
    FIELD(MojoBundle.messagePointer("settings.mojo.color.field"), DefaultLanguageHighlighterColors.INSTANCE_FIELD),
    CONSTANT(MojoBundle.messagePointer("settings.mojo.color.constant"), DefaultLanguageHighlighterColors.CONSTANT),
    MODULE(MojoBundle.messagePointer("settings.mojo.color.module"), DefaultLanguageHighlighterColors.CLASS_REFERENCE),

    FUNCTION(MojoBundle.messagePointer("settings.mojo.color.function.declaration"), DefaultLanguageHighlighterColors.FUNCTION_DECLARATION),
    NESTED_FUNCTION(MojoBundle.messagePointer("settings.mojo.color.function.nested"), DefaultLanguageHighlighterColors.FUNCTION_DECLARATION),
    METHOD(MojoBundle.messagePointer("settings.mojo.color.method.declaration"), DefaultLanguageHighlighterColors.INSTANCE_METHOD),
    // The generic FUNCTION_CALL fallback is unstyled in some color schemes.
    FUNCTION_CALL(MojoBundle.messagePointer("settings.mojo.color.function.call"), DefaultLanguageHighlighterColors.FUNCTION_DECLARATION),
    METHOD_CALL(MojoBundle.messagePointer("settings.mojo.color.method.call"), DefaultLanguageHighlighterColors.INSTANCE_METHOD),

    PARAMETER(MojoBundle.messagePointer("settings.mojo.color.parameter"), DefaultLanguageHighlighterColors.PARAMETER),
    MUTABLE_PARAMETER(MojoBundle.messagePointer("settings.mojo.color.parameter.mutable"), DefaultLanguageHighlighterColors.PARAMETER),
    SELF_PARAMETER(MojoBundle.messagePointer("settings.mojo.color.parameter.self"), DefaultLanguageHighlighterColors.KEYWORD),
    TYPE_PARAMETER(
        MojoBundle.messagePointer("settings.mojo.color.parameter.type"),
        TextAttributesKey.find("TYPE_PARAMETER_NAME_ATTRIBUTES"),
    ),

    STRUCT(MojoBundle.messagePointer("settings.mojo.color.struct"), DefaultLanguageHighlighterColors.CLASS_NAME),
    TRAIT(MojoBundle.messagePointer("settings.mojo.color.trait"), DefaultLanguageHighlighterColors.INTERFACE_NAME),
    TYPE_ALIAS(MojoBundle.messagePointer("settings.mojo.color.type.alias"), DefaultLanguageHighlighterColors.INTERFACE_NAME),
    BUILTIN_TYPE(MojoBundle.messagePointer("settings.mojo.color.type.builtin"), DefaultLanguageHighlighterColors.KEYWORD),
    TYPE_REFERENCE(MojoBundle.messagePointer("settings.mojo.color.type.reference"), DefaultLanguageHighlighterColors.CLASS_REFERENCE),
    KEYWORD_ARGUMENT(MojoBundle.messagePointer("settings.mojo.color.keyword.argument"), DefaultLanguageHighlighterColors.PARAMETER),
    DECORATOR(MojoBundle.messagePointer("settings.mojo.color.decorator"), DefaultLanguageHighlighterColors.METADATA),
    MLIR_SYMBOL(MojoBundle.messagePointer("settings.mojo.color.mlir.symbol"), DefaultLanguageHighlighterColors.METADATA),

    KEYWORD(MojoBundle.messagePointer("settings.mojo.color.keyword"), DefaultLanguageHighlighterColors.KEYWORD),
    NUMBER(MojoBundle.messagePointer("settings.mojo.color.number"), DefaultLanguageHighlighterColors.NUMBER),
    STRING(MojoBundle.messagePointer("settings.mojo.color.string"), DefaultLanguageHighlighterColors.STRING),
    LINE_COMMENT(MojoBundle.messagePointer("settings.mojo.color.line.comment"), DefaultLanguageHighlighterColors.LINE_COMMENT),
    OPERATION_SIGN(MojoBundle.messagePointer("settings.mojo.color.operation.sign"), DefaultLanguageHighlighterColors.OPERATION_SIGN),
    PARENTHESES(MojoBundle.messagePointer("settings.mojo.color.parentheses"), DefaultLanguageHighlighterColors.PARENTHESES),
    BRACKETS(MojoBundle.messagePointer("settings.mojo.color.brackets"), DefaultLanguageHighlighterColors.BRACKETS),
    BRACES(MojoBundle.messagePointer("settings.mojo.color.braces"), DefaultLanguageHighlighterColors.BRACES),
    COMMA(MojoBundle.messagePointer("settings.mojo.color.comma"), DefaultLanguageHighlighterColors.COMMA),
    DOT(MojoBundle.messagePointer("settings.mojo.color.dot"), DefaultLanguageHighlighterColors.DOT),
    SEMICOLON(MojoBundle.messagePointer("settings.mojo.color.semicolon"), DefaultLanguageHighlighterColors.SEMICOLON),
    BAD_CHARACTER(MojoBundle.messagePointer("settings.mojo.color.bad.character"), HighlighterColors.BAD_CHARACTER),
    ;

    val textAttributesKey: TextAttributesKey =
        TextAttributesKey.createTextAttributesKey("com.github.petrmakhnev.intellijmojo.$name", fallback)
    val attributesDescriptor = AttributesDescriptor(displayName, textAttributesKey)
}
