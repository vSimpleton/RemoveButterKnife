package com.imiyar.removebutterknife.utils

import java.lang.StringBuilder
import java.util.*
import java.util.regex.Pattern

/**
 * 下划线转驼峰
 */
fun String.underLineToHump(): String {
    val regex = "_(.)"
    val mather = Pattern.compile(regex).matcher(this)
    var result = this
    while (mather.find()) {
        val target = mather.group(1)
        result = result.replace("_${target}", target.substring(0, 1).uppercase())
    }
    return result
}

/**
 * 根据字符串获取R.layout.后的布局文件
 */
fun String.getLayoutRes(): String {
    val regex = ".+R\\.layout\\.(\\w+)"
    val target = Pattern.compile(regex).matcher(this)
    if (target.find()) {
        return target.group(1)
    }
    return ""
}

/**
 * 首字母大写并拼接ViewBinding后缀
 */
fun String.withViewBinding(): String {
    val builder = StringBuilder()
    builder.append(this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }).append("Binding")
    return builder.toString()
}