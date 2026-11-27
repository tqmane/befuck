package dev.tqmane.befuck.runtime

import android.content.Context
import java.lang.reflect.Modifier
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Signature-based WorkManager bridge shared across upload and background sync flows. */
internal object HostWorkManager {
    data class EnqueuedWork(val uniqueName: String, val id: UUID)

    fun instance(context: Context, classLoader: ClassLoader): Any {
        val workManagerClass = Class.forName("androidx.work.WorkManager", false, classLoader)
        val implementationClass = Class.forName("androidx.work.impl.WorkManagerImpl", false, classLoader)
        HostReflection.allMethods(implementationClass).singleOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&
                workManagerClass.isAssignableFrom(method.returnType)
        }?.let { factory ->
            factory.isAccessible = true
            return requireNotNull(factory.invoke(null, context)) { "WorkManager is not initialized" }
        }

        val companionClass = Class.forName("androidx.work.WorkManager\$Companion", false, classLoader)
        val companionField = HostReflection.allFields(workManagerClass).singleOrNull { field ->
            Modifier.isStatic(field.modifiers) && field.type == companionClass
        } ?: error("WorkManager companion instance unavailable")
        companionField.isAccessible = true
        val companion = companionField.get(null) ?: error("WorkManager companion is null")
        val factory = HostReflection.allMethods(companionClass).singleOrNull { method ->
            method.parameterTypes.contentEquals(arrayOf(Context::class.java)) &&
                workManagerClass.isAssignableFrom(method.returnType)
        } ?: error("WorkManager companion context factory unavailable")
        factory.isAccessible = true
        return requireNotNull(factory.invoke(companion, context)) { "WorkManager is not initialized" }
    }

    fun buildStringData(classLoader: ClassLoader, entries: Map<String, String>): Any {
        val dataClass = Class.forName("androidx.work.Data", false, classLoader)
        val dataBuilderClass = Class.forName("androidx.work.Data\$Builder", false, classLoader)
        val dataBuilder = dataBuilderClass.getDeclaredConstructor().newInstance()
        val putString = HostReflection.allMethods(dataBuilderClass).singleOrNull { method ->
            method.parameterCount == 2 &&
                method.parameterTypes[0] == String::class.java &&
                (method.parameterTypes[1] == String::class.java || method.parameterTypes[1] == Any::class.java) &&
                (method.returnType == Void.TYPE || dataBuilderClass.isAssignableFrom(method.returnType))
        } ?: error("Data.Builder string writer unavailable")
        putString.isAccessible = true
        entries.forEach { (key, value) -> putString.invoke(dataBuilder, key, value) }

        val build = HostReflection.allMethods(dataBuilderClass).singleOrNull { method ->
            method.parameterCount == 0 && method.returnType == dataClass
        } ?: error("Data.Builder build method unavailable")
        build.isAccessible = true
        val data = build.invoke(dataBuilder)
        require(dataClass.isInstance(data)) { "WorkManager Data builder returned ${data?.javaClass?.name}" }
        return data!!
    }

    fun buildConnectedConstraints(classLoader: ClassLoader): Any {
        val constraintsBuilderClass = Class.forName("androidx.work.Constraints\$Builder", false, classLoader)
        val constraintsBuilder = constraintsBuilderClass.getDeclaredConstructor().newInstance()
        val networkTypeClass = Class.forName("androidx.work.NetworkType", false, classLoader)
        val connectedNetwork = HostReflection.namedEnumValue(networkTypeClass, "CONNECTED")
        val setRequiredNetworkType = HostReflection.allMethods(constraintsBuilderClass).singleOrNull { method ->
            method.parameterTypes.contentEquals(arrayOf(networkTypeClass)) &&
                (method.returnType == Void.TYPE || constraintsBuilderClass.isAssignableFrom(method.returnType))
        } ?: error("Constraints.Builder network-type setter unavailable")
        setRequiredNetworkType.isAccessible = true
        setRequiredNetworkType.invoke(constraintsBuilder, connectedNetwork)

        val constraintsClass = Class.forName("androidx.work.Constraints", false, classLoader)
        val build = HostReflection.allMethods(constraintsBuilderClass).singleOrNull { method ->
            method.parameterCount == 0 && method.returnType == constraintsClass
        } ?: error("Constraints.Builder build method unavailable")
        build.isAccessible = true
        return requireNotNull(build.invoke(constraintsBuilder)) { "Host WorkManager constraints builder unavailable" }
    }

    fun enqueueUniqueOneTimeWork(
        context: Context,
        classLoader: ClassLoader,
        workerClass: Class<*>,
        uniqueName: String,
        inputEntries: Map<String, String>,
        initialDelayHours: Long? = null,
        linearBackoff: Boolean = false,
        tag: String? = null,
        replaceExisting: Boolean = false,
    ): EnqueuedWork {
        val dataClass = Class.forName("androidx.work.Data", false, classLoader)
        val inputData = buildStringData(classLoader, inputEntries)
        val requestClass = Class.forName("androidx.work.OneTimeWorkRequest", false, classLoader)
        val workRequestClass = Class.forName("androidx.work.WorkRequest", false, classLoader)
        val requestBuilderClass = Class.forName("androidx.work.OneTimeWorkRequest\$Builder", false, classLoader)
        val baseBuilderClass = Class.forName("androidx.work.WorkRequest\$Builder", false, classLoader)
        val requestConstructor = requestBuilderClass.declaredConstructors.singleOrNull { constructor ->
            constructor.parameterTypes.contentEquals(arrayOf(Class::class.java))
        } ?: error("OneTimeWorkRequest.Builder worker constructor unavailable")
        requestConstructor.isAccessible = true
        val requestBuilder = requestConstructor.newInstance(workerClass)

        val setInputData = HostReflection.allMethods(requestBuilderClass).singleOrNull { method ->
            method.parameterTypes.contentEquals(arrayOf(dataClass)) &&
                baseBuilderClass.isAssignableFrom(method.returnType)
        } ?: error("OneTimeWorkRequest.Builder input-data method unavailable")
        setInputData.isAccessible = true
        setInputData.invoke(requestBuilder, inputData)

        if (initialDelayHours != null) {
            val setDelay = HostReflection.allMethods(requestBuilderClass).singleOrNull { method ->
                method.parameterTypes.contentEquals(arrayOf(Long::class.javaPrimitiveType, TimeUnit::class.java)) &&
                    baseBuilderClass.isAssignableFrom(method.returnType)
            } ?: error("Host WorkManager initial delay setter unavailable")
            setDelay.isAccessible = true
            setDelay.invoke(requestBuilder, initialDelayHours, TimeUnit.HOURS)
        }

        val constraintsClass = Class.forName("androidx.work.Constraints", false, classLoader)
        val constraints = buildConnectedConstraints(classLoader)
        val workSpecClass = Class.forName("androidx.work.impl.model.WorkSpec", false, classLoader)
        val workSpecField = HostReflection.allFields(requestBuilderClass).singleOrNull { it.type == workSpecClass }
            ?: error("OneTimeWorkRequest.Builder WorkSpec field unavailable")
        workSpecField.isAccessible = true
        val workSpec = workSpecField.get(requestBuilder)
        val constraintsField = HostReflection.allFields(workSpecClass).singleOrNull { it.type == constraintsClass }
            ?: error("WorkSpec constraints field unavailable")
        constraintsField.isAccessible = true
        constraintsField.set(workSpec, constraints)

        if (linearBackoff) {
            val backoffClass = Class.forName("androidx.work.BackoffPolicy", false, classLoader)
            val linear = HostReflection.namedEnumValue(backoffClass, "LINEAR")
            val methods = HostReflection.allMethods(requestBuilderClass)
            val durationClass = runCatching { Class.forName("java.time.Duration", false, classLoader) }.getOrNull()
            val invoked = methods.singleOrNull { method ->
                method.parameterTypes.contentEquals(arrayOf(backoffClass, Long::class.javaPrimitiveType, TimeUnit::class.java)) &&
                    baseBuilderClass.isAssignableFrom(method.returnType)
            }?.apply { isAccessible = true }?.invoke(requestBuilder, linear, 10_000L, TimeUnit.MILLISECONDS)
                ?: durationClass?.let { duration ->
                    methods.singleOrNull { method ->
                        method.parameterTypes.contentEquals(arrayOf(backoffClass, duration)) &&
                            baseBuilderClass.isAssignableFrom(method.returnType)
                    }
                }?.apply { isAccessible = true }?.invoke(requestBuilder, linear, java.time.Duration.ofMillis(10_000L))
                ?: methods.singleOrNull { method ->
                    method.parameterTypes.contentEquals(arrayOf(backoffClass)) &&
                        baseBuilderClass.isAssignableFrom(method.returnType)
                }?.apply { isAccessible = true }?.invoke(requestBuilder, linear)
            checkNotNull(invoked) { "WorkRequest.Builder linear backoff setter unavailable" }
        }

        if (tag != null) {
            HostReflection.allMethods(requestBuilderClass).singleOrNull { method ->
                method.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                    baseBuilderClass.isAssignableFrom(method.returnType)
            }?.apply { isAccessible = true }?.invoke(requestBuilder, tag)
        }

        val buildCandidates = HostReflection.allMethods(requestBuilderClass).filter { method ->
            method.parameterCount == 0 && workRequestClass.isAssignableFrom(method.returnType)
        }
        val build = buildCandidates.singleOrNull { method ->
            method.name == "build" && method.declaringClass == baseBuilderClass
        } ?: buildCandidates.singleOrNull { method ->
            method.name == "a" && method.declaringClass == baseBuilderClass && Modifier.isFinal(method.modifiers)
        } ?: baseBuilderClass.declaredMethods.singleOrNull { method ->
            method.parameterCount == 0 && method.returnType.name == "androidx.work.WorkRequest" &&
                Modifier.isFinal(method.modifiers)
        } ?: error("WorkRequest.Builder build operation unavailable; candidates=${buildCandidates.map { it.toGenericString() }}")
        build.isAccessible = true
        val request = build.invoke(requestBuilder)
        require(requestClass.isInstance(request)) { "WorkRequest.Builder returned ${request?.javaClass?.name}" }

        val requestIdField = HostReflection.allFields(requestClass).singleOrNull { it.type == UUID::class.java }
            ?: error("OneTimeWorkRequest UUID field unavailable")
        requestIdField.isAccessible = true
        val requestId = requestIdField.get(request) as? UUID
            ?: error("OneTimeWorkRequest UUID is empty")

        val workManagerClass = Class.forName("androidx.work.WorkManager", false, classLoader)
        val workManager = instance(context, classLoader)
        val policyClass = Class.forName("androidx.work.ExistingWorkPolicy", false, classLoader)
        val policy = HostReflection.namedEnumValue(policyClass, if (replaceExisting) "REPLACE" else "KEEP")
        val operationClass = Class.forName("androidx.work.Operation", false, classLoader)
        val enqueue = HostReflection.allMethods(workManagerClass).singleOrNull { method ->
            method.parameterCount == 3 &&
                method.parameterTypes[0] == String::class.java &&
                method.parameterTypes[1] == policyClass &&
                method.parameterTypes[2].isInstance(request) &&
                operationClass.isAssignableFrom(method.returnType)
        } ?: error("WorkManager unique-work enqueue method unavailable")
        enqueue.isAccessible = true
        enqueue.invoke(workManager, uniqueName, policy, request)
        return EnqueuedWork(uniqueName, requestId)
    }

    fun cancelByNames(context: Context, classLoader: ClassLoader, names: List<String>) {
        val workManager = instance(context, classLoader)
        val managerType = Class.forName("androidx.work.WorkManager", false, classLoader)
        val operationType = Class.forName("androidx.work.Operation", false, classLoader)
        val actions = HostReflection.allMethods(managerType).filter {
            it.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                operationType.isAssignableFrom(it.returnType)
        }
        // Minified WorkManager retains both cancelUniqueWork and cancelAllWorkByTag.
        // Calling both with private, unique BeFuck names is safe, independent of R8 names.
        for (name in names) {
            for (action in actions) {
                action.isAccessible = true
                awaitOperation(action.invoke(workManager, name), operationType)
            }
        }
    }

    private fun awaitOperation(operation: Any?, operationType: Class<*>) {
        if (operation == null) return
        val resultMethod = HostReflection.allMethods(operationType).singleOrNull { method ->
            method.parameterCount == 0 && java.util.concurrent.Future::class.java.isAssignableFrom(method.returnType)
        } ?: return
        resultMethod.isAccessible = true
        val future = resultMethod.invoke(operation) as? java.util.concurrent.Future<*> ?: return
        future.get(15L, TimeUnit.SECONDS)
    }

    fun workResultSuccess(classLoader: ClassLoader): Any {
        val resultType = Class.forName("androidx.work.ListenableWorker\$Result", false, classLoader)
        val successType = Class.forName("androidx.work.ListenableWorker\$Result\$Success", false, classLoader)
        val factory = HostReflection.allMethods(resultType).singleOrNull {
            Modifier.isStatic(it.modifiers) && it.parameterCount == 0 &&
                successType.isAssignableFrom(it.returnType)
        } ?: error("WorkManager Result.Success factory is missing")
        factory.isAccessible = true
        return requireNotNull(factory.invoke(null))
    }
}
