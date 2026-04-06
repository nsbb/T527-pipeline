package com.t527.smart_service.nlu

data class Params(
    val location: String? = null,
    val temperature: Int? = null,
    val acMode: String? = null,
    val acWind: String? = null,
    val timeRef: String? = null
)

object ParameterExtractor {
    private val locRegex = Regex("(거실|안방|침실|주방|전체|각\\s*실|식탁)")
    private val tempNumRegex = Regex("(\\d+)\\s*도")
    private val tempKorRegex = Regex("(이십|삼십|십)\\s*(일|이|삼|사|오|육|칠|팔|구)?\\s*도")
    private val acModeRegex = Regex("(냉방|제습|송풍|자동)\\s*(모드)?")
    private val acWindRegex = Regex("(약풍|중풍|강풍)")
    private val timeRefRegex = Regex("(오늘|내일|모레|주말|이번\\s*주|다음\\s*주)")

    private val korNumMap = mapOf(
        "일" to 1, "이" to 2, "삼" to 3, "사" to 4, "오" to 5,
        "육" to 6, "칠" to 7, "팔" to 8, "구" to 9
    )
    private val korTensMap = mapOf("십" to 10, "이십" to 20, "삼십" to 30)

    fun extract(text: String): Params {
        val location = locRegex.find(text)?.groupValues?.get(1)

        var temperature: Int? = null
        tempNumRegex.find(text)?.let {
            temperature = it.groupValues[1].toIntOrNull()?.coerceIn(18, 30)
        }
        if (temperature == null) {
            tempKorRegex.find(text)?.let { m ->
                val tens = korTensMap[m.groupValues[1]] ?: 0
                val ones = m.groupValues.getOrNull(2)?.let { korNumMap[it] } ?: 0
                temperature = (tens + ones).coerceIn(18, 30)
            }
        }

        val acMode = acModeRegex.find(text)?.groupValues?.get(1)
        val acWind = acWindRegex.find(text)?.groupValues?.get(1)
        val timeRef = timeRefRegex.find(text)?.groupValues?.get(1)

        return Params(location, temperature, acMode, acWind, timeRef)
    }
}
