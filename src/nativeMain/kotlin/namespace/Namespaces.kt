package namespace

import spec.Namespace

/**
 * Check if a namespace type exists in the list
 */
fun hasNamespace(
    namespaces: List<Namespace>?,
    type: String,
): Boolean = namespaces?.any { it.type == type } ?: false
