package b.language.server

import b.language.server.dataStorage.Settings
import com.google.gson.Gson
import com.google.gson.JsonObject


/**
 * Takes a json and tries to cast it into a settings objects
 * @param json the json object
 * @return the settings object
 */
fun castJsonToSetting(json : JsonObject) : Settings {
    // Missing keys fall back to the defaults instead of failing the settings future.
    val defaults = Settings()
    fun value(vararg keys : String) = keys.firstNotNullOfOrNull { key -> json.get(key)?.takeUnless { it.isJsonNull } }
    return Settings(
            strictChecks = value("strictChecks")?.asBoolean ?: defaults.strictChecks,
            wdChecks = value("wdChecks")?.asBoolean ?: defaults.wdChecks,
            performanceHints = value("performanceHints")?.asBoolean ?: defaults.performanceHints,
            probHome = value("probHome", "proBHome")?.asString ?: defaults.probHome,
            debugMode = value("debugMode")?.asBoolean ?: defaults.debugMode,
            poTimeout = value("poTimeout")?.asInt ?: defaults.poTimeout,
            modelCheckStates = value("modelCheckStates")?.asInt ?: defaults.modelCheckStates)
}


