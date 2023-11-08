package com.alivehealth.motiontest.ml

import android.graphics.Bitmap
import com.alivehealth.motiontest.data.Person

/**
 * 用于指定类必须实现的方法，但不提供方法的实现。在这个例子中，PoseDetector  接口具体定义了两个方法，estimatePoses
 * lastInferenceTimeNanos 同时扩展了 AutoCloseable 接口，也必须实现 AutoCloseable 的 close() 方法。
 */

interface PoseDetector : AutoCloseable {

    fun estimatePoses(bitmap: Bitmap): List<Person>

    fun lastInferenceTimeNanos(): Long
}
