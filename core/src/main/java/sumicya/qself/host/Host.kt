/*
 * Qself — a free, modern Xposed module for QQ.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package sumicya.qself.host

import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import sumicya.qself.log.QLog

/**
 * Central host (QQ) class resolution — the single place where reflection
 * happens.
 *
 * Everything is resolved once, at feature init time, and cached. Feature
 * code afterwards deals with plain [Class]/[Method]/[Field] references:
 * no per-call reflection, no scattered `Class.forName`.
 *
 * QQ renames or moves classes occasionally, so [resolve] accepts a list of
 * candidate FQCNs (newest first) and remembers both hits and misses.
 */
class Host(
    private val packageName: String,
    /**
     * The *host application's* classloader. Without it `Class.forName` inside
     * a hooked process resolves against the module's own classloader, which
     * knows nothing about QQ — every lookup misses and the module concludes it
     * is running on the wrong QQ generation (that is exactly what happened on
     * the device: NT features were skipped, classic ones no-op'd, and the boot
     * log said nothing useful). Always pass the host Application's loader.
     */
    private val classLoader: ClassLoader? = null,
) {

    private val classCache = LinkedHashMap<String, Class<*>?>()
    private val methodCache = LinkedHashMap<MethodKey, Method?>()
    private val fieldCache = LinkedHashMap<FieldKey, Field?>()
    private val constructorCache = LinkedHashMap<MethodKey, Constructor<*>?>()

    /**
     * Load the first candidate that exists. The result (including null) is
     * cached, so repeated misses are cheap.
     */
    /**
     * Which QQ generation this process hosts (NT or pre-NT). Determined from
     * class presence, cached by [resolve]; used to skip features that were
     * written for the other generation instead of failing them one by one.
     */
    val generation: sumicya.qself.util.HostGeneration by lazy {
        sumicya.qself.util.HostGeneration.detect { name -> resolve(name) }
    }

    fun resolve(vararg fqcn: String): Class<*>? {
        for (name in fqcn) {
            classCache[name]?.let { return it }
            if (classCache.containsKey(name)) {
                return null
            }
            val cls = try {
                if (classLoader != null) {
                    Class.forName(name, false, classLoader)
                } else {
                    QLog.w("Host", "no host classloader; falling back to Class.forName")
                    Class.forName(name)
                }
            } catch (t: Throwable) {
                null
            }
            classCache[name] = cls
            if (cls != null) {
                QLog.d("Host", "resolved $name")
                return cls
            }
        }
        QLog.d("Host", "none of ${fqcn.joinToString()} found")
        return null
    }

    /**
     * Resolve a class whose name may carry a synthetic inner-class suffix
     * (`$1`, `$2`, ...). QQ obfuscation often turns `Foo` into `Foo$1`.
     */
    fun resolveSynthetic(baseName: String, vararg suffixes: Int): Class<*>? {
        val tried = ArrayList<String>(suffixes.size + 1)
        tried += baseName
        for (suffix in suffixes) {
            tried += "$baseName$$suffix"
        }
        for (name in tried) {
            resolve(name)?.let { return it }
        }
        return null
    }

    /** [resolve] that throws when nothing resolves — the common init pattern. */
    fun require(vararg fqcn: String): Class<*> =
        resolve(*fqcn)
            ?: throw ClassNotFoundException("none of ${fqcn.joinToString(", ")} found in $packageName")

    fun method(cls: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        val key = MethodKey(cls.name, name, paramTypes.map { it.name })
        methodCache[key]?.let { return it }
        val m = try {
            cls.getDeclaredMethod(name, *paramTypes).also { it.isAccessible = true }
        } catch (t: Throwable) {
            null
        }
        methodCache[key] = m
        return m
    }

    fun requireMethod(cls: Class<*>, name: String, vararg paramTypes: Class<*>): Method =
        method(cls, name, *paramTypes)
            ?: throw NoSuchMethodException("${cls.name}#$name(${paramTypes.joinToString { it.name }})")

    /** All declared methods named [name] (optionally restricted to [paramCount]). */
    fun methods(cls: Class<*>, name: String, paramCount: Int = -1): List<Method> =
        cls.declaredMethods.filter {
            it.name == name && (paramCount < 0 || it.parameterTypes.size == paramCount)
        }.also { it.forEach { m -> m.isAccessible = true } }

    /** All declared methods returning [returnType] (optionally restricted to [paramCount]). */
    fun methodsReturning(cls: Class<*>, returnType: Class<*>, paramCount: Int = -1): List<Method> =
        cls.declaredMethods.filter {
            it.returnType == returnType && (paramCount < 0 || it.parameterTypes.size == paramCount)
        }.also { it.forEach { m -> m.isAccessible = true } }

    fun field(cls: Class<*>, name: String): Field? {
        val key = FieldKey(cls.name, name)
        fieldCache[key]?.let { return it }
        var current: Class<*>? = cls
        while (current != null) {
            val f = try {
                current.getDeclaredField(name)
            } catch (t: Throwable) {
                null
            }
            if (f != null) {
                f.isAccessible = true
                fieldCache[key] = f
                return f
            }
            current = if (current.isInterface) null else current.superclass
        }
        fieldCache[key] = null
        return null
    }

    fun constructor(cls: Class<*>, vararg paramTypes: Class<*>): Constructor<*>? {
        val key = MethodKey(cls.name, "<init>", paramTypes.map { it.name })
        constructorCache[key]?.let { return it }
        val c = try {
            cls.getDeclaredConstructor(*paramTypes).also { it.isAccessible = true }
        } catch (t: Throwable) {
            null
        }
        constructorCache[key] = c
        return c
    }

    /** Read a (possibly private, possibly inherited) field. */
    fun read(field: Field, target: Any?): Any? = field.get(target)

    fun write(field: Field, target: Any?, value: Any?) {
        field.set(target, value)
    }

    private class MethodKey(
        val cls: String,
        val name: String,
        val params: List<String>,
    ) {
        override fun equals(other: Any?): Boolean =
            other is MethodKey && other.cls == cls && other.name == name && other.params == params

        override fun hashCode(): Int = (cls.hashCode() * 31 + name.hashCode()) * 31 + params.hashCode()
    }

    private class FieldKey(val cls: String, val name: String) {
        override fun equals(other: Any?): Boolean = other is FieldKey && other.cls == cls && other.name == name

        override fun hashCode(): Int = cls.hashCode() * 31 + name.hashCode()
    }
}
