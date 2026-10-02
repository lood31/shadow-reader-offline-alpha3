package com.shadowreader.app.pronunciation

import java.util.concurrent.locks.ReentrantLock

/** Held by the physical native worker, including cancellation cleanup. */
object InferenceGate { val lock = ReentrantLock(true) }
