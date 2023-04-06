/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.io.jvm.javaio

import io.ktor.io.*
import kotlinx.coroutines.*
import java.io.*
import java.io.IOException

private val CloseToken = Any()
private val FlushToken = Any()

internal class OutputAdapter(parent: Job?, private val channel: ByteWriteChannel) : OutputStream() {

    private val loop = object : BlockingAdapter(parent) {
        override suspend fun loop() {
            try {
                while (true) {
                    val task = rendezvous(0)
                    if (task === CloseToken) {
                        break
                    } else if (task === FlushToken) {
                        channel.flush()
                        channel.closedCause?.let { throw it }
                    } else if (task is ByteArray) channel.writeByteArray(task, offset, length)
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    channel.close(t)
                }
                throw t
            } finally {
                channel.close()
                channel.closedCause?.let { throw it }
            }
        }
    }

    private var single: ByteArray? = null

    @Synchronized
    override fun write(b: Int) {
        val buffer = single ?: ByteArray(1).also { single = it }
        buffer[0] = b.toByte()
        loop.submitAndAwait(buffer, 0, 1)
    }

    @Synchronized
    override fun write(b: ByteArray, off: Int, len: Int) {
        val array = b.sliceArray(off until off + len)
        loop.submitAndAwait(array, 0, len)
    }

    @Synchronized
    override fun flush() {
        loop.submitAndAwait(FlushToken)
    }

    @Synchronized
    override fun close() {
        try {
            loop.submitAndAwait(CloseToken)
            loop.shutdown()
        } catch (t: Throwable) {
            throw IOException(t)
        }
    }
}