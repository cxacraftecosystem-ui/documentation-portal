package com.fieldrepository.app.data

internal fun unopenedTokenStore(): TokenStore {
    val field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
    field.isAccessible = true
    val unsafe = field.get(null)
    val allocate = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
    return allocate.invoke(unsafe, TokenStore::class.java) as TokenStore
}
