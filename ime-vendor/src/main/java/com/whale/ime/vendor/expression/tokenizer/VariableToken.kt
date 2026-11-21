package com.whale.ime.vendor.expression.tokenizer

/**
 * represents a setVariable used in an expression
 */
class VariableToken ( @JvmField val name: String) : Token(TOKEN_VARIABLE)
