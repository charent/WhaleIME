package com.whale.ime.vendor.expression.tokenizer

import com.whale.ime.vendor.expression.function.Function

class FunctionToken(@JvmField val function: Function) : Token(TOKEN_FUNCTION)
