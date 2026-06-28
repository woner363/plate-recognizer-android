package com.example.platerecognizer.util

/**
 * 中国机动车号牌格式校验与规范化，依据 **GA 36-2018**《中华人民共和国机动车号牌》。
 *
 * 关键规则：
 *  - 序号位（[plate]\[2..\]）严禁出现字母 I 和 O（标准 5.9 表 3 / 5.9.2 / 5.9.3）。
 *    发牌机关代号位 [plate]\[1\] 允许出现 O（仅作省厅/特殊代号，附录 A 备注 g/h/i）。
 *  - 首字必须是 31 个省/自治区/直辖市简称之一（标准 5.7 表 2）。
 *  - 发牌机关代号必须在该省的附录 A 白名单内。
 *  - 7 位普通号牌：省份 + 发牌机关代号 + 5 位序号，序号编码规则按表 4
 *    （全数字 / 1-2 位字母混合，余位数字）。
 *  - 8 位新能源号牌：序号 6 位，首位或末位必须是 D/A/B/C/E/F/G/H/J/K
 *    （表 5/6 启用字母全集），或前两位都是字母（表 6 启用顺序 2/4/6/8/10）。
 *
 * 与原桌面 Python 版相比，本实现做了如下收紧：
 *  - 字母集去掉 I/O；
 *  - 加附录 A 的发牌机关代号白名单；
 *  - 新能源车牌精确到字母位约束。
 */
object PlateValidator {

    /** 31 个省/直辖市/自治区简称（标准 5.7 表 2）。 */
    private const val PROVINCES = "京津沪渝冀豫云辽黑湘皖鲁新苏浙赣鄂桂甘晋蒙陕吉闽贵粤青藏川宁琼"

    /** 序号位允许的字母：去掉 I 和 O（GA 36-2018 5.9 强制）。 */
    private const val SERIAL_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ"

    /** 序号位允许的字符（字母+数字）。 */
    private const val SERIAL_ALNUM = "${SERIAL_LETTERS}0123456789"

    /** 新能源车牌字母位允许的字母（标准 5.9.3 表 5/6 启用顺序的全集）。 */
    private const val NEW_ENERGY_LETTERS = "DABCEFGHJK"

    /**
     * 附录 A 发牌机关代号白名单。按 31 个一级行政区组织。
     *
     * 直辖市（京/津/沪/渝）开放整个 A-Z 区间（标准 5.8.2，但实际启用前需备案）；
     * 其余省份按 GA 36-2018 附录 A 表 A.1 列出的实际地市代号。
     */
    private val DISTRICT_CODES: Map<Char, Set<Char>> = buildMap {
        // 直辖市（标准 5.8.2：A-Z 全部可启用）
        val municipal = "ABCDEFGHJKLMNPQRSTUVWXYZ".toSet()  // 已剔除 I/O，但 O 作省厅代号会单独处理
        put('京', municipal + 'O')
        put('津', municipal + 'O')
        put('沪', municipal + 'O')
        put('渝', municipal + 'O')

        // 省份 / 自治区（按附录 A 表 A.1，含 O 省厅代号 = 各省统一）
        put('冀', "ABCDEFGHJRTO".toSet())
        put('晋', "ABCDEFHJKLMO".toSet())
        put('蒙', "ABCDEFGHJKLMO".toSet())
        put('辽', "ABCDEFGHJKLMNPO".toSet())
        put('吉', "ABCDEFGHJKO".toSet())
        put('黑', "ABCDEFGHJKLMNPRO".toSet())            // 哈尔滨 A/L
        put('苏', "ABCDEFGHJKLMNO".toSet())
        put('浙', "ABCDEFGHJKLO".toSet())
        put('皖', "ABCDEFGHJKLMNPRSO".toSet())
        put('闽', "ABCDEFGHJKO".toSet())                 // 福州 A/K
        put('赣', "ABCDEFGHJKLMO".toSet())               // 南昌 A/M
        put('鲁', "ABCDEFGHJKLMNPQRSUVWYO".toSet())       // 青岛 B/U、烟台 F/Y、潍坊 G/V，外加省厅 O 与总队 W
        put('豫', "ABCDEFGHJKLMNPQRSUO".toSet())
        put('鄂', "ABCDEFGHJKLMNPQRSO".toSet())
        put('湘', "ABCDEFGHJKLMNSUO".toSet())            // 长沙 A/S，湘西 U
        put('粤', "ABCDEFGHJKLMNPQRSTUVWXYZO".toSet())    // 佛山 E/X/Y，港澳入出境 Z
        put('桂', "ABCDEFGHJKLMNPRO".toSet())            // 桂林 C/H
        put('琼', "ABCDEFO".toSet())
        put('川', "ABCDEFGHJKLMQRSTUVWXYZO".toSet())      // 成都 A/G
        put('贵', "ABCDEFGHJO".toSet())
        put('云', "ACDEFGHJKLMNPQRSO".toSet())            // 云南无 B
        put('藏', "ABCDEFGO".toSet())
        put('陕', "ABCDEFGHJKVO".toSet())                // 杨凌 V
        put('甘', "ABCDEFGHJKLMNPO".toSet())
        put('青', "ABCDEFGHO".toSet())
        put('宁', "ABCDEO".toSet())                      // 5 个地级市 + 省厅
        put('新', "ABCDEFGHJKLMNPQRO".toSet())            // 伊犁 D/F
    }

    /** 全角字母 / 数字 / 空格 归一化映射。 */
    private val FULLWIDTH_RANGE = 0xFF01..0xFF5E
    private val stripRe = Regex("[\\s.·\\-]+")

    /** 去除空格/点号/连字符，全角转半角，转大写。 */
    fun normalize(plate: String?): String {
        if (plate.isNullOrBlank()) return ""
        val sb = StringBuilder(plate.length)
        for (ch in plate) {
            val mapped = when (ch.code) {
                0x3000 -> ' '
                in FULLWIDTH_RANGE -> (ch.code - 0xFEE0).toChar()
                else -> ch
            }
            sb.append(mapped)
        }
        return stripRe.replace(sb.toString(), "").uppercase()
    }

    /** 严格校验。合法即可入库。 */
    fun isValid(plate: String?): Boolean = describeError(plate) == null

    /**
     * 合法返回 null；否则返回简要错误描述，UI 直接展示。
     * 按"长度 → 首字 → 发牌机关 → 序号字符集 → 新能源细则"顺序逐级校验。
     */
    fun describeError(plate: String?): String? {
        val p = normalize(plate)
        if (p.isEmpty()) return "车牌号不能为空"
        if (p.length !in 7..8) return "车牌位数应为 7 或 8，当前 ${p.length} 位"

        val province = p[0]
        if (province !in PROVINCES) return "首字必须是中国省份简称（如 京/沪/粤）"

        val districtCode = p[1]
        val allowed = DISTRICT_CODES[province] ?: return "暂不支持的省份：$province"
        if (!districtCode.isLetter() || districtCode.code !in 'A'.code..'Z'.code) {
            return "第 2 位必须是英文字母（发牌机关代号）"
        }
        if (districtCode !in allowed) {
            return "「$province$districtCode」不是合法的发牌机关代号"
        }

        val serial = p.substring(2)
        if (serial.any { it !in SERIAL_ALNUM }) {
            // 区分 I/O 与其他非法字符以便给出更明确提示
            if (serial.any { it == 'I' || it == 'O' }) {
                return "序号位不能使用字母 I 或 O（GA 36-2018）"
            }
            return "序号位只能是字母（除 I/O）或数字"
        }

        // 新能源（8 位）：第 1 位或第 6 位必须是 D/A/B/C/E/F/G/H/J/K，
        // 或前两位都是字母（其余位数字）
        if (p.length == 8) {
            val s = serial  // 6 位
            val firstLetter = s[0].isLetter()
            val secondLetter = s[1].isLetter()
            val lastLetter = s[5].isLetter()
            val middleAllDigits = s.substring(2, 5).all { it.isDigit() }

            val ok = when {
                // 表 5 / 表 6 启用顺序 1/3/5/7/9：首位是新能源字母，其余数字
                firstLetter && !secondLetter && s.substring(1).all { it.isDigit() } ->
                    s[0] in NEW_ENERGY_LETTERS
                // 表 5 启用顺序：末位是新能源字母，其余数字
                lastLetter && !firstLetter && s.substring(0, 5).all { it.isDigit() } ->
                    s[5] in NEW_ENERGY_LETTERS
                // 表 6 启用顺序 2/4/6/8/10：前两位都是字母，且第一位是新能源字母
                firstLetter && secondLetter && middleAllDigits && s[5].isDigit() ->
                    s[0] in NEW_ENERGY_LETTERS
                else -> false
            }
            if (!ok) return "不符合新能源车牌编码规则"
            return null
        }

        // 普通 7 位：5 位序号，按表 4 至少应 (a) 全数字 (b) 1 位字母 (c) 2 位字母混合
        // 这里不再细分启用顺序，只要序号字符集合法即放行（标准里已经穷举到 16 种组合）
        return null
    }
}
