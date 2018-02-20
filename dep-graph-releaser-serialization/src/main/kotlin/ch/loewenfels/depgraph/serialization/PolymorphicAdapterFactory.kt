package ch.loewenfels.depgraph.serialization

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.lang.reflect.Modifier
import java.lang.reflect.Type

/**
 * A [JsonAdapter.Factory] which relies on the order of the fields to provide fast processing for an [abstractType]
 * (interface or abstract class).
 *
 * This factory does not support processing of concrete classes with subtypes (introduce an interface,
 * that is better anyway).
 */
class PolymorphicAdapterFactory<T : Any>(private val abstractType: Class<T>) : JsonAdapter.Factory {
    init {
        require(abstractType.isInterface || Modifier.isAbstract(abstractType.modifiers)) {
            "Do not use ${PolymorphicAdapterFactory::class.simpleName} for non abstract types (neither an interface nor an abstract class).\n" +
                "Given: ${abstractType.name}"
        }
    }

    override fun create(type: Type, annotations: MutableSet<out Annotation>, moshi: Moshi): JsonAdapter<*>? {
        // we only deal with the abstract type here
        if (abstractType != type) {
            return null
        }
        return PolymorphicAdapter(abstractType, moshi)
    }

    private class PolymorphicAdapter<T : Any>(private val abstractType: Class<T>, private val moshi: Moshi) : NonNullJsonAdapter<T>() {

        override fun fromJson(reader: JsonReader): T? {
            reader.beginObject()

            //If you make changes here, then you have to make changes in toJson
            checkName(reader, TYPE)
            val entityName = reader.nextString()
            val runtimeClass = loadClass(entityName)
            checkName(reader, PAYLOAD)
            val entity = moshi.adapter(runtimeClass).fromJson(reader)

            reader.endObject()
            return entity
        }

        private fun checkName(reader: JsonReader, expectedName: String) {
            val nextName = reader.nextName()
            require(nextName == expectedName) {
                """Cannot read polymorphic type, field order matters
                    |Expected: $expectedName
                    |Given: $nextName
                """.trimMargin()
            }
        }

        private fun loadClass(commandName: String?): Class<T> {
            val runtimeClass = Class.forName(commandName)
            require(abstractType.isAssignableFrom(runtimeClass)) {
                """"Found a wrong type, cannot deserialize JSON
                    |Expected: ${abstractType.name}
                    |Given: ${runtimeClass.name}
                """.trimMargin()
            }
            @Suppress("UNCHECKED_CAST" /* we checked it above */)
            return runtimeClass as Class<T>
        }

        override fun toJsonNonNull(writer: JsonWriter, value: T) {
            val runtimeClass = value::class.java
            val adapter: JsonAdapter<T> = getAdapter(runtimeClass)
            writer.writeObject {
                //If you make changes here, then you have to make changes in fromJson
                writer.name(TYPE)
                writer.value(runtimeClass.name)
                writer.name(PAYLOAD)
                adapter.toJson(writer, value)
            }
        }

        private fun getAdapter(runtimeClass: Class<out T>): JsonAdapter<T> {
            require(!runtimeClass.isAnonymousClass) {
                "Cannot serialize an anonymous class, given: ${runtimeClass.name}"
            }
            @Suppress("UNCHECKED_CAST" /* entity is of type T, should be fine, required for toJson */)
            return moshi.adapter(runtimeClass) as JsonAdapter<T>
        }
    }

    companion object {
        const val TYPE = "t"
        const val PAYLOAD = "p"
    }
}
