package dev.shield.ui.common

import android.content.Context
import android.text.format.DateUtils
import dev.shield.R
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Format {
    fun number(n: Long): String = NumberFormat.getIntegerInstance().format(n)
    fun number(n: Int): String = number(n.toLong())

    fun time(t: Long): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(t))
    fun dateTime(t: Long): String = SimpleDateFormat("dd.MM. HH:mm", Locale.getDefault()).format(Date(t))

    fun ago(ctx: Context, t: Long): String {
        if (t <= 0) return ctx.getString(R.string.never)
        val diff = System.currentTimeMillis() - t
        return when {
            diff < 60_000 -> ctx.getString(R.string.just_now)
            else -> DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        }
    }

    fun duration(ctx: Context, since: Long): String {
        val m = (System.currentTimeMillis() - since) / 60_000
        return when {
            m < 60 -> ctx.getString(R.string.duration_min, m)
            m < 60 * 48 -> ctx.getString(R.string.duration_h, m / 60)
            else -> ctx.getString(R.string.duration_d, m / 1440)
        }
    }
}
