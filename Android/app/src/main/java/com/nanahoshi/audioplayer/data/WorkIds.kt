package com.nanahoshi.audioplayer.data

/**
 * RJ / VJ / BJ 作品编号的统一解析。编号是字符串标识，
 * 任何路径都不得经由整数往返丢失前导零。
 */
object WorkIds {
    private val WORK_ID = Regex("(?i)(?:RJ|VJ|BJ)\\d+")
    private val WORK_ID_STRICT = Regex("(?:RJ|VJ|BJ)\\d+")
    private val PREFIXED_RAW = Regex("(?i)(RJ|VJ|BJ)(\\d+)")
    private val PREFIXED_DIGITS = Regex("(?i)(RJ|VJ|BJ)0*(\\d+)")

    /** 输入中第一个作品编号（含前缀），已转为大写。 */
    fun find(input: String): String? = WORK_ID.find(input)?.value?.uppercase()

    /** 严格的整串作品编号判断，用于目录名和已规范化值。 */
    fun matches(value: String): Boolean = WORK_ID_STRICT.matches(value)

    /** 规范化：统一大写、去掉前缀后的前导零，保留原始前缀。 */
    fun canonical(raw: String): String {
        val match = PREFIXED_DIGITS.find(raw) ?: return raw.trim().uppercase()
        val digits = match.groupValues[2].trimStart('0').ifEmpty { "0" }
        return match.groupValues[1].uppercase() + digits
    }

    /** 严格整串前缀与数字拆分；不补零，保留原文数字。 */
    data class Parts(val prefix: String, val digits: String)

    fun parts(raw: String): Parts? {
        val match = PREFIXED_RAW.matchEntire(raw.trim()) ?: return null
        return Parts(match.groupValues[1].uppercase(), match.groupValues[2])
    }

    /** 兼容旧接口的多种写法：原样、8 位、6 位和去零编号。 */
    fun candidates(raw: String): List<String> {
        val match = PREFIXED_DIGITS.find(raw) ?: return listOf(raw.trim().uppercase())
        val prefix = match.groupValues[1].uppercase()
        val digits = match.groupValues[2].trimStart('0').ifEmpty { "0" }
        return listOf(
            raw.trim().uppercase(),
            prefix + digits.padStart(8, '0'),
            prefix + digits.padStart(6, '0'),
            prefix + digits,
        ).distinct()
    }

    /** 从用户输入中取编号；没有前缀时按 RJ 处理，纯数字仍保留原位数。 */
    fun fromInput(input: String): String? {
        find(input)?.let { return it }
        val digits = input.filter { it.isDigit() }
        return digits.takeIf { it.isNotEmpty() }?.let { "RJ$it" }
    }
}