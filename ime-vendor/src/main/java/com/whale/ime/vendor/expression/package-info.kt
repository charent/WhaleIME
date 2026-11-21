/*
 * 表达式求值器，转写自 EvalEx（com.udojava.evalex，Apache-2.0），本项目转为 Kotlin。
 * 上游版本：上游未标注版本（仓库内无许可证头，版本次第无法确认）。
 * 本地改动：以 ArrayStack + ShuntingYard + Tokenizer 的分层保留了 EvalEx 的 API 形状
 * （Expression / ExpressionBuilder / Functions / Operators），但只保留了数字键盘算式所需的部分：
 * 无变量注册、无自定义函数注册的外部入口。
 * 后续与上游比对时再逐条补全，不确定的条目宁可不写。
 */
@file:Suppress("unused")

package com.whale.ime.vendor.expression
