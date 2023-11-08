package com.alivehealth.motiontest.data
import android.graphics.PointF

/**
 * bodyPart: 这是 BodyPart 类型的属性，表示这个关键点对应的身体部位。
 * coordinate: 这是 PointF 类型的属性，表示这个关键点在图像中的坐标位置。
 *  BodyPart 枚举用来标识关键点，
 */

data class KeyPoint(val bodyPart: BodyPart, var coordinate: PointF, val score: Float)