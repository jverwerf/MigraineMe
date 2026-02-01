package com.migraineme

object MetricSettingsMapHelper {

    fun mapKey(metric: String, preferredSource: String?): String {
        return if (preferredSource != null) {
            "${metric}_${preferredSource}"
        } else {
            "${metric}_null"
        }
    }

    fun toMap(
        settings: List<EdgeFunctionsService.MetricSettingResponse>
    ): Map<String, EdgeFunctionsService.MetricSettingResponse> {
        return settings.associateBy { mapKey(it.metric, it.preferredSource) }
    }

    fun isEnabled(
        map: Map<String, EdgeFunctionsService.MetricSettingResponse>,
        metric: String,
        preferredSource: String?,
        defaultValue: Boolean
    ): Boolean {
        return map[mapKey(metric, preferredSource)]?.enabled ?: defaultValue
    }
}
