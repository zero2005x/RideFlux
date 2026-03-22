package com.wheellog.next.core.common.ext

/**
 * Common unit conversion extensions for EUC telemetry.
 */

/** Convert km/h to mph. */
fun Float.kmhToMph(): Float = this * 0.621371f

/** Convert mph to km/h. */
fun Float.mphToKmh(): Float = this / 0.621371f

/** Convert Celsius to Fahrenheit. */
fun Float.celsiusToFahrenheit(): Float = this * 9f / 5f + 32f

/** Convert kilometres to miles. */
fun Float.kmToMiles(): Float = this * 0.621371f
