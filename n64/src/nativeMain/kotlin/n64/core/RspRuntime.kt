@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package n64.core

import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toLong

internal var rspHostRef: RSP? = null

private fun rspExecCallout(op: Int): Int = rspHostRef!!.dynExec(op)

internal fun rspExecPtr(): Long = staticCFunction(::rspExecCallout).toLong()
