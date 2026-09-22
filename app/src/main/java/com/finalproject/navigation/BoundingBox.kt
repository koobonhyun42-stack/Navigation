package com.finalproject.navigation

data class BoundingBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val cnf: Float,
    val cls: Int,
    val cx: Float = (x1 + x2) / 2f,
    val cy: Float = (y1 + y2) / 2f,
    val w: Float = x2 - x1,
    val h: Float = y2 - y1,
    val clsName: String = "",
    var distanceMeter: Float = -1f
)