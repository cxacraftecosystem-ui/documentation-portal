package com.fieldrepository.app.explorer

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class QFeature(val id: String)
