package com.fieldrepository.app.questionnaire

/**
 * The parity registry's marker (`shared/questionnaire-features.json`, UNIFY 5.3): a test that
 * exercises a questionnaire feature on Android carries `@QFeature("<id>")`, and
 * `backend/tests/test_questionnaire_feature_parity.py` reads the marker from the source. One test may
 * carry several.
 */
@Repeatable
@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class QFeature(val id: String)
