package dev.tqmane.befuck.symbols

import java.lang.reflect.Field
import java.lang.reflect.Modifier

/** Optional native permission launchers; these never gate the shared map renderer. */
internal object PostMapPermissionActions {
    private data class Recipe(val viewModel: String, val request: String, val launchClass: String, val launchMethod: String)

    fun resolve(loader: ClassLoader, versionName: String?, versionCode: Long, viewModelField: Field): ((Any) -> Unit)? {
        // Both supplied APKs merge four suspend lambdas. Selector 3 requests permission;
        // selector 2 calls Google's camera API and must not be used by our renderer.
        // There is no safe generic coroutine ABI to assume on uninspected future APKs.
        val recipe = when (versionName to versionCode) {
            "3.97.0" to 3597523L -> Recipe("emg", "xlg", "gy9", "p")
            "3.97.1" to 3599414L -> Recipe("xmg", "pmg", "tx9", "u")
            else -> return null
        }
        return runCatching {
            fun type(name: String) = Class.forName(name, false, loader)
            val vm = type(recipe.viewModel)
            val baseVm = type("androidx.lifecycle.ViewModel")
            val scopeType = type("androidx.lifecycle.viewmodel.internal.CloseableCoroutineScope")
            require(viewModelField.type == vm && baseVm.isAssignableFrom(vm) && !Modifier.isStatic(viewModelField.modifiers))
            val scope = type("androidx.lifecycle.ViewModelKt").declaredMethods.single {
                Modifier.isStatic(it.modifiers) && it.returnType == scopeType && it.parameterTypes.contentEquals(arrayOf(baseVm))
            }.apply { isAccessible = true }
            val requestType = type(recipe.request)
            val request = requestType.declaredConstructors.single {
                val args = it.parameterTypes
                args.size == 3 && args[0] == vm && args[1].isInterface && args[2] == Int::class.javaPrimitiveType
            }.apply { isAccessible = true }
            val launch = type(recipe.launchClass).declaredMethods.single {
                val args = it.parameterTypes
                it.name == recipe.launchMethod && Modifier.isStatic(it.modifiers) && !it.returnType.isPrimitive &&
                    args.size == 5 && args[0].isAssignableFrom(scopeType) && args[1].isInterface &&
                    args[3].isInterface && args[3].isAssignableFrom(requestType) && args[4] == Int::class.javaPrimitiveType
            }.apply { isAccessible = true }
            viewModelField.isAccessible = true
            val action: (Any) -> Unit = { owner ->
                val instance = viewModelField.get(owner)
                launch.invoke(null, scope.invoke(null, instance), null, null, request.newInstance(instance, null, 3), 3)
            }
            action
        }.getOrNull()
    }
}
