package com.t527.smart_service.nlu

object IntentResponse {
    private val templates = mapOf(
        "light_control_on" to "{loc}조명을 켜드릴게요.",
        "light_control_off" to "{loc}조명을 꺼드릴게요.",
        "light_control_dim" to "{loc}조명을 조절합니다.",
        "light_query" to "{loc}조명 상태를 확인합니다.",
        "light_schedule" to "{loc}조명 예약을 설정합니다.",
        "heat_control_up" to "{loc}난방 온도를 올립니다.",
        "heat_control_down" to "{loc}난방 온도를 내립니다.",
        "heat_temp" to "{loc}난방 온도를 {temp}도로 설정합니다.",
        "heat_query" to "{loc}난방 상태를 확인합니다.",
        "heat_schedule" to "{loc}난방 예약을 설정합니다.",
        "ac_control_on" to "{loc}에어컨을 켜드릴게요.",
        "ac_control_off" to "{loc}에어컨을 꺼드릴게요.",
        "ac_control_cool" to "{loc}에어컨 냉방을 시작합니다.",
        "ac_control_warm" to "{loc}에어컨 난방을 시작합니다.",
        "ac_temp" to "{loc}에어컨 온도를 {temp}도로 설정합니다.",
        "ac_query" to "{loc}에어컨 상태를 확인합니다.",
        "ac_schedule" to "{loc}에어컨 예약을 설정합니다.",
        "ac_mode" to "{loc}에어컨을 {mode} 모드로 전환합니다.",
        "ac_wind" to "{loc}에어컨 풍량을 {wind}으로 조절합니다.",
        "vent_control_on" to "{loc}환기를 시작합니다.",
        "vent_control_off" to "{loc}환기를 중지합니다.",
        "vent_query" to "{loc}환기 상태를 확인합니다.",
        "vent_schedule" to "{loc}환기 예약을 설정합니다.",
        "gas_control_lock" to "가스를 잠급니다.",
        "gas_control_open" to "가스를 열겠습니다.",
        "gas_query" to "가스 상태를 확인합니다.",
        "door_open" to "현관문을 열겠습니다.",
        "door_query" to "현관 상태를 확인합니다.",
        "curtain_control_open" to "{loc}커튼을 열겠습니다.",
        "curtain_control_close" to "{loc}커튼을 닫겠습니다.",
        "security_mode_out" to "외출 모드를 설정합니다.",
        "security_mode_home" to "재실 모드를 설정합니다.",
        "security_query" to "보안 상태를 확인합니다.",
        "weather_today" to "오늘 날씨를 알려드릴게요.",
        "weather_tomorrow" to "내일 날씨를 알려드릴게요.",
        "weather_week" to "이번 주 날씨를 알려드릴게요.",
        "weather_rain" to "강수 정보를 확인합니다.",
        "weather_temperature" to "기온 정보를 확인합니다.",
        "airquality_query" to "공기질을 확인합니다.",
        "energy_query" to "에너지 사용량을 확인합니다.",
        "home_status_query" to "집 상태를 확인합니다.",
        "time_query" to "현재 시각을 알려드릴게요.",
        "manual_query" to "사용 설명서를 안내합니다.",
        "lobby_open" to "로비 출입문을 열겠습니다.",
        "info_query" to "정보를 조회합니다.",
        "settings" to "설정을 변경합니다.",
        "general" to "말씀을 이해했습니다.",
        "unknown" to "다시 한번 말씀해 주세요."
    )

    fun getResponse(intent: String, params: Params = Params()): String {
        val template = templates[intent] ?: "다시 한번 말씀해 주세요."
        return template
            .replace("{loc}", params.location?.let { "${it} " } ?: "")
            .replace("{temp}", params.temperature?.toString() ?: "24")
            .replace("{mode}", params.acMode ?: "냉방")
            .replace("{wind}", params.acWind ?: "중풍")
    }

    fun getTtsKey(intent: String, params: Params): String {
        if (intent == "heat_temp" && params.temperature != null) return "heat_temp_${params.temperature}"
        if (intent == "ac_temp" && params.temperature != null) return "ac_temp_${params.temperature}"
        if (intent == "ac_mode" && params.acMode != null) {
            val m = when (params.acMode) { "냉방" -> "cooling"; "제습" -> "dehumid"; "송풍" -> "fan"; else -> "auto" }
            return "ac_mode_$m"
        }
        if (intent == "ac_wind" && params.acWind != null) {
            val w = when (params.acWind) { "약풍" -> "low"; "중풍" -> "mid"; else -> "high" }
            return "ac_wind_$w"
        }
        return intent
    }
}
