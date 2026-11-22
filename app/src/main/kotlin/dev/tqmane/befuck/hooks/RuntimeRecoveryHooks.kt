package dev.tqmane.befuck.hooks

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IInterface
import android.os.Trace
import dev.tqmane.befuck.runtime.RuntimeKnowledge
import dev.tqmane.befuck.symbols.HostMethod
import dev.tqmane.befuck.symbols.ResolvedSymbols
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.util.concurrent.Executor

/** Runtime crash recovery, null-argument healing, Room/Protobuf guards, and Media3 receiver bridge. */
internal class RuntimeRecoveryHooks(private val host: HookHost) {
    private val protobufMessageInfo = ThreadLocal<Any?>()
    @Volatile private var protobufNullFieldLogged = false

    fun installNullTraceSectionGuard() {
        try {
            val trace = Class.forName("android.os.Trace", false, null)
            val beginSection = trace.getDeclaredMethod("beginSection", String::class.java)
            host.hook(beginSection)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    if (chain.getArg(0) == null) {
                        chain.proceed(arrayOf("BeRealTrace:unresolved"))
                    } else {
                        chain.proceed()
                    }
                }
            host.info("Hooked Android Trace.beginSection null-name guard")
        } catch (error: Throwable) {
            host.error("Could not hook Android Trace.beginSection null-name guard", error)
        }
    }

    fun installComposeTracingContextGuard(classLoader: ClassLoader) {
        val mappings = host.mappings ?: return
        try {
            val tracingContext = Class.forName("androidx.compose.runtime.TracingContext", false, classLoader)
            val unit = mappings.kotlinUnit(classLoader)
            val beginTrace = tracingContext.getDeclaredMethod(mappings.methodName(HostMethod.ComposeTraceBegin))
            if (beginTrace.returnType != Any::class.java || Modifier.isStatic(beginTrace.modifiers)) {
                throw NoSuchMethodException("Compose tracing begin signature")
            }
            host.hook(beginTrace)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept {
                    Trace.beginSection("Compose:coroutineTracing")
                    unit
                }
            host.info("Hooked Compose TracingContext null section name")
        } catch (error: Throwable) {
            host.error("Could not hook Compose TracingContext null section name", error)
        }
    }

    fun installRuntimeRecoveryGuards(classLoader: ClassLoader) {
        val mappings = host.mappings ?: return
        try {
            val attach = Binder::class.java.getDeclaredMethod("attachInterface", IInterface::class.java, String::class.java)
            host.hook(attach).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                if (chain.getArg(0) != null && chain.getArg(1) == null) {
                    val recovered = RuntimeKnowledge.recoverArgument("binder_descriptor")
                    if (recovered != null) {
                        val args = chain.args.toTypedArray()
                        args[1] = recovered
                        return@intercept chain.proceed(args)
                    }
                    // Persist unresolved descriptors before a later native Parcel call can abort the process.
                    RuntimeKnowledge.flushDiagnostics()
                }
                chain.proceed()
            }
        } catch (failure: Throwable) {
            host.error("Could not install Binder descriptor recovery", failure)
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            try {
                RuntimeKnowledge.recordFailure(failure)
                RuntimeKnowledge.flushDiagnostics()
            } finally {
                previous?.uncaughtException(thread, failure)
            }
        }

        try {
            val builder = StringBuilder::class.java.getDeclaredConstructor(String::class.java)
            hookNullStringArg(builder, 0, "string_builder_prefix")
            for (name in arrayOf("endsWith", "startsWith")) {
                hookNullStringArg(String::class.java.getDeclaredMethod(name, String::class.java), 0, "string_$name")
            }
            host.info("Installed null-argument recovery and version-scoped JSONL diagnostics")
        } catch (failure: Throwable) {
            host.error("Could not install a String null-argument recovery hook", failure)
        }

        if (mappings.requiresRuntimeRecovery) {
            try {
                val property = mappings.analyticsPropertyMethod(classLoader) ?: return
                hookNullStringArg(property, 1, "analytics_key")
            } catch (failure: Throwable) {
                host.error("Could not install analytics-key recovery", failure)
            }
        }
    }

    private fun hookNullStringArg(target: java.lang.reflect.Executable, argIndex: Int, key: String) {
        host.hook(target).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
            if (chain.getArg(argIndex) == null) {
                val recovered = RuntimeKnowledge.recoverArgument(key)
                if (recovered != null) {
                    val args = chain.args.toTypedArray()
                    args[argIndex] = recovered
                    return@intercept chain.proceed(args)
                }
            }
            chain.proceed()
        }
    }

    fun installAnalyticsNullKeyGuard(classLoader: ClassLoader) {
        val mappings = host.mappings ?: return
        try {
            val identify = mappings.analyticsIdentifyMethod(classLoader) ?: return
            host.hook(identify)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val argument = chain.getArg(0)
                    if (argument is Map<*, *> && argument.containsKey(null)) {
                        val sanitized = LinkedHashMap<Any?, Any?>(argument)
                        val removed = if (sanitized.containsKey(null)) 1 else 0
                        sanitized.remove(null)
                        val args = chain.args.toTypedArray()
                        args[0] = sanitized
                        host.info("Removed $removed null-key analytics property before Identify")
                        return@intercept chain.proceed(args)
                    }
                    chain.proceed()
                }
            host.info("Hooked analytics Identify map for null property keys")
        } catch (error: Throwable) {
            host.error("Could not hook analytics Identify map", error)
        }
    }

    fun installProtobufNullFieldProbe(classLoader: ClassLoader) {
        val mappings = host.mappings ?: return
        try {
            val buildSchema = mappings.protobufSchemaMethod(classLoader) ?: return
            host.hook(buildSchema)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val previous = protobufMessageInfo.get()
                    protobufMessageInfo.set(chain.getArg(0))
                    try {
                        chain.proceed()
                    } finally {
                        if (previous == null) protobufMessageInfo.remove() else protobufMessageInfo.set(previous)
                    }
                }

            val getField = mappings.protobufFieldLookupMethod(classLoader) ?: return
            host.hook(getField)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val classArgument = chain.getArg(0)
                    if (chain.getArg(1) == null && classArgument is Class<*>) {
                        val inferredField = inferMissingProtoField(classArgument)
                        if (inferredField != null) {
                            host.info("Recovered protobuf field name for ${classArgument.name}: ${inferredField.name}")
                            return@intercept inferredField
                        }
                        if (!protobufNullFieldLogged) {
                            protobufNullFieldLogged = true
                            val fields = classArgument.declaredFields.joinToString(", ") { "${it.name}:${it.type.name}" }
                            host.info("Protobuf schema requested a null field name for ${classArgument.name}; declaredFields=[$fields]")
                        }
                    }
                    chain.proceed()
                }
            host.info("Hooked protobuf schema field lookup with metadata-based recovery")
        } catch (error: Throwable) {
            host.error("Could not hook protobuf schema field lookup", error)
        }
    }

    private fun inferMissingProtoField(messageClass: Class<*>): Field? {
        val messageInfo = protobufMessageInfo.get() ?: return null
        return try {
            val objectNamesField = messageInfo.javaClass.getDeclaredField("c").apply { isAccessible = true }
            val value = objectNamesField.get(messageInfo) as? Array<*> ?: return null
            val knownNames = value.filterIsInstance<String>().toHashSet()
            val candidates = messageClass.declaredFields.filter { field ->
                val name = field.name
                !Modifier.isStatic(field.modifiers) && name.endsWith("_") && !knownNames.contains(name)
            }
            if (candidates.size == 1) candidates[0] else null
        } catch (error: Throwable) {
            host.error("Could not infer protobuf field name for ${messageClass.name}", error)
            null
        }
    }

    fun installRoomNullColumnGuard(classLoader: ClassLoader) {
        try {
            val sqliteStatement = Class.forName("androidx.sqlite.SQLiteStatement", false, classLoader)
            val statementUtil = Class.forName("androidx.room.util.SQLiteStatementUtil", false, classLoader)
            val findColumn = statementUtil.getDeclaredMethod("b", sqliteStatement, String::class.java)
            val getColumnCount = sqliteStatement.getMethod("getColumnCount")
            val getColumnName = sqliteStatement.getMethod("getColumnName", Int::class.javaPrimitiveType)

            host.hook(findColumn)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val statement = chain.getArg(0)
                    if (statement != null && chain.getArg(1) == null) {
                        try {
                            val columnCount = (getColumnCount.invoke(statement) as Number).toInt()
                            val columns = ArrayList<String?>(columnCount)
                            var idIndex = -1
                            for (index in 0 until columnCount) {
                                val name = getColumnName.invoke(statement, index) as? String
                                columns.add(name)
                                if (name == "id") idIndex = index
                            }
                            if (columnCount == 6 && idIndex >= 0 &&
                                columns.contains("ownerId") &&
                                columns.contains("postId") &&
                                columns.contains("url") &&
                                columns.contains("emoji") &&
                                columns.contains("postedAt")
                            ) {
                                host.info("Recovered null Room column key as RealMojiEntity.id from statement schema")
                                return@intercept idIndex
                            }
                            if (host.markOnce("roomNullColumnProbe")) {
                                host.warn(
                                    "Room null column lookup: statementClass=${statement.javaClass.name}, " +
                                        "sql=${readRoomStatementSql(statement)}, columns=$columns",
                                )
                            }
                        } catch (inspectionError: Throwable) {
                            host.error("Could not inspect Room statement with a null column key", inspectionError)
                        }
                    }
                    chain.proceed()
                }
            host.info("Hooked Room null-column lookup with a RealMojiEntity schema fallback")
        } catch (error: Throwable) {
            host.error("Could not hook Room null-column lookup", error)
        }
    }

    private fun readRoomStatementSql(statement: Any): String {
        var type: Class<*>? = statement.javaClass
        while (type != null) {
            val candidateNames = if (type.name == "androidx.sqlite.driver.SupportSQLiteStatement") {
                arrayOf("b", "sql")
            } else {
                arrayOf("sql")
            }
            for (candidateName in candidateNames) {
                try {
                    val sqlField = type.getDeclaredField(candidateName).apply { isAccessible = true }
                    val sql = sqlField.get(statement)
                    if (sql is String) return sql
                } catch (_: NoSuchFieldException) {
                } catch (error: Throwable) {
                    return "<unreadable:${error.javaClass.simpleName}>"
                }
            }
            type = type.superclass
        }
        return "<unavailable:${statement.javaClass.name}>"
    }

    fun installCameraOriginSentinelGuard(symbols: ResolvedSymbols?) {
        val parsers = symbols?.cameraOriginParserMethods.orEmpty()
        if (parsers.isEmpty()) {
            host.info("[SymbolResolver] Camera origin guard disabled: route parsers unresolved")
            return
        }

        var hooked = 0
        for (parseFromBundle in parsers) {
            try {
                host.hook(parseFromBundle)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept { chain ->
                        val argument = chain.getArg(0)
                        if (argument is Bundle) {
                            val origin = argument.getString("origin")
                            if (origin != null && (origin.trim().isEmpty() || origin.trim() == "null")) {
                                val sanitized = Bundle(argument)
                                sanitized.remove("origin")
                                val args = chain.args.toTypedArray()
                                args[0] = sanitized
                                host.logOnce(
                                    "cameraOriginSentinel",
                                    "Removed null-like camera origin before enum parsing; " +
                                        "letting the original parser use its default enum",
                                )
                                return@intercept chain.proceed(args)
                            }
                        }
                        chain.proceed()
                    }
                hooked++
                host.info("Hooked camera route parser ${parseFromBundle.declaringClass.name}.${parseFromBundle.name}")
            } catch (error: Throwable) {
                host.error("Could not hook camera route parser $parseFromBundle", error)
            }
        }
        host.info("Camera origin guard active on $hooked validated Bundle parser(s)")
    }

    fun installMedia3NetworkTypeReceiverHook(classLoader: ClassLoader) {
        try {
            val observerClass = Class.forName("androidx.media3.common.util.NetworkTypeObserver", false, classLoader)
            val receiverClass = Class.forName("androidx.media3.common.util.NetworkTypeObserver\$Receiver", false, classLoader)
            val getInstance = observerClass.getDeclaredMethod("getInstance", Context::class.java).apply { isAccessible = true }
            val handleConnectivityChange = observerClass.getDeclaredMethod(
                "handleConnectivityActionBroadcast",
                Context::class.java,
            ).apply { isAccessible = true }
            val backgroundExecutor = observerClass.getDeclaredField("backgroundExecutor").apply { isAccessible = true }
            val onReceive = receiverClass.getDeclaredMethod(
                "onReceive",
                Context::class.java,
                Intent::class.java,
            ).apply { isAccessible = true }

            host.hook(onReceive)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept { chain ->
                    val context = chain.getArg(0) as? Context ?: return@intercept null
                    try {
                        val observer = getInstance.invoke(null, context)
                        val executor = backgroundExecutor.get(observer) as? Executor
                            ?: throw IllegalStateException("Media3 NetworkTypeObserver executor unavailable")
                        executor.execute {
                            try {
                                handleConnectivityChange.invoke(observer, context)
                            } catch (failure: Throwable) {
                                val cause = (failure as? InvocationTargetException)?.cause ?: failure
                                host.error("Media3 connectivity update failed without PairIP VMRunner", cause)
                            }
                        }
                        host.logOnce(
                            "media3NetworkObserverDispatch",
                            "Dispatched Media3 connectivity callback without PairIP VMRunner",
                        )
                    } catch (failure: Throwable) {
                        val cause = (failure as? InvocationTargetException)?.cause ?: failure
                        host.error("Could not dispatch Media3 connectivity callback without PairIP VMRunner", cause)
                    }
                    null
                }
            host.info("Hooked Media3 NetworkTypeObserver receiver to its Java implementation")
        } catch (error: Throwable) {
            host.error("Could not hook Media3 NetworkTypeObserver receiver", error)
        }
    }
}
