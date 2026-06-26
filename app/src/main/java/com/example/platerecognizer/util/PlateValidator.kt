package com.example.platerecognizer.util

/**
 * 中国车牌号格式校验与规范化。
 * - 普通蓝/黄牌：省份 + 字母 + 5位字母数字  (7位)
 * - 新能源车牌：省份 + 字母 + 6位字母数字  (8位)
 *
 * 与桌面 Python 版 plate_validator.py 保持一致。
 */
object PlateValidator {

    private const val PROVINCES = "京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼"

    private val normal = Regex("^[$PROVINCES][A-Z][A-Z0-9]{5}$")
    private val newEnergy = Regex("^[$PROVINCES][A-Z][A-Z0-9]{6}$")
    private val stripRe = Regex("[\\s.·\\-]+")

    /** 去除空格/点号/连字符，转大写。 */
    fun normalize(plate: String?): String {
        if (plate.isNullOrBlank()) return ""
        return stripRe.replace(plate, "").uppercase()
    }

    /** 是否合法。 */
    fun isValid(plate: String?): Boolean {
        val p = normalize(plate)
        if (p.isEmpty()) return false
        return normal.matches(p) || newEnergy.matches(p)
    }

    /** 合法返回 null；否则返回错误描述。 */
    fun describeError(plate: String?): String? {
        val p = normalize(plate)
        if (p.isEmpty()) return "车牌号不能为空"
        if (p.length !in 7..8) return "车牌位数应为 7 或 8，当前 ${p.length} 位"
        if (p[0] !in PROVINCES) return "首字必须是中国省份简称（如 京/沪/粤）"
        if (!isValid(p)) return "格式不符合中国车牌规则"
        return null
    }
}
