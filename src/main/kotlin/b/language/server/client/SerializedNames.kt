package b.language.server.client

import com.google.gson.Gson
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter

/**
 * Writes the constants of an enum by their [SerializedName], as the client reads them. lsp4j's Gson
 * writes enums as numbers otherwise; put `@JsonAdapter(SerializedNames::class)` on the fields that
 * hold such an enum.
 */
class SerializedNames : TypeAdapterFactory {
    override fun <T> create(
        gson: Gson,
        type: TypeToken<T>,
    ): TypeAdapter<T>? {
        val raw = type.rawType
        if (!raw.isEnum) return null
        @Suppress("UNCHECKED_CAST")
        val names: Map<T, String> =
            (raw.enumConstants as Array<T>).associateWith { constant ->
                val name = (constant as Enum<*>).name
                raw.getField(name).getAnnotation(SerializedName::class.java)?.value ?: name
            }
        val constants = names.entries.associate { (constant, name) -> name to constant }
        return object : TypeAdapter<T>() {
            override fun write(
                out: JsonWriter,
                value: T?,
            ) {
                if (value == null) out.nullValue() else out.value(names[value])
            }

            override fun read(reader: JsonReader): T? {
                if (reader.peek() == JsonToken.NULL) {
                    reader.nextNull()
                    return null
                }
                return constants[reader.nextString()]
            }
        }
    }
}
