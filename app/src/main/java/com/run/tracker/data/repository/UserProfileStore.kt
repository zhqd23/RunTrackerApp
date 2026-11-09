package com.run.tracker.data.repository

import android.content.Context
import android.content.SharedPreferences

/**
 * 用户基础资料（目前只有体重），用于卡路里估算。
 *
 * 卡路里 = MET × 体重 × 时长，体重差 10kg 一次 30 分钟跑步就能差出 50~60 kcal，
 * 所以不能再写死默认值，必须让用户自己填。
 */
class UserProfileStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var weightKg: Double
        get() = prefs.getFloat(KEY_WEIGHT, DEFAULT_WEIGHT_KG.toFloat()).toDouble()
        set(value) {
            prefs.edit()
                .putFloat(KEY_WEIGHT, value.coerceIn(MIN_WEIGHT_KG, MAX_WEIGHT_KG).toFloat())
                .apply()
        }

    companion object {
        private const val PREFS_NAME = "user_profile"
        private const val KEY_WEIGHT = "weight_kg"

        const val DEFAULT_WEIGHT_KG = 65.0
        const val MIN_WEIGHT_KG = 30.0
        const val MAX_WEIGHT_KG = 200.0
    }
}
