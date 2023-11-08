package com.alivehealth.motiontest.tracker

import com.alivehealth.motiontest.data.Person

data class Track(
    val person: Person,
    val lastTimeStamp: Long
)