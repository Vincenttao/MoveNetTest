package com.alivehealth.motiontest.data

/**
 * BodyPart 是一个枚举类，用来表示人体的各个部位。fromInt: 这是一个方法，它接受一个整数 position 并返回映射中
 * 与该位置相对应的 BodyPart。如果给定位置不存在，getValue 方法将抛出异常。
 * 每个枚举实例都有一个对应的 position 属性，这是一个整数值，用来唯一标识这个枚举实例。
 * * 在 BodyPart 枚举类中，还定义了一个 companion object，这是 Kotlin 中的一个单例对象，可以包含方法和属性。
 * 在这个 companion object 中，定义了两个属性：
 * * map: 这是一个通过调用 values().associateBy(BodyPart::position) 创建的映射，
 * 它将每个 BodyPart 的 position 作为键，BodyPart 实例自身作为值。
 * 这允许我们通过整数位置来快速查找对应的 BodyPart 实例。
 *
 *
 */
enum class BodyPart(val position: Int) {
    NOSE(0),
    LEFT_EYE(1),
    RIGHT_EYE(2),
    LEFT_EAR(3),
    RIGHT_EAR(4),
    LEFT_SHOULDER(5),
    RIGHT_SHOULDER(6),
    LEFT_ELBOW(7),
    RIGHT_ELBOW(8),
    LEFT_WRIST(9),
    RIGHT_WRIST(10),
    LEFT_HIP(11),
    RIGHT_HIP(12),
    LEFT_KNEE(13),
    RIGHT_KNEE(14),
    LEFT_ANKLE(15),
    RIGHT_ANKLE(16);
    companion object{
        private val map = values().associateBy(BodyPart::position)
        fun fromInt(position: Int): BodyPart = map.getValue(position)
    }
}
