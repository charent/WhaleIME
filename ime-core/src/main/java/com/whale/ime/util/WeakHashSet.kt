

package com.whale.ime.util

import java.util.Collections
import java.util.WeakHashMap

@Suppress("FunctionName")
fun <T> WeakHashSet(): MutableSet<T> = Collections.newSetFromMap(WeakHashMap<T, Boolean>())
